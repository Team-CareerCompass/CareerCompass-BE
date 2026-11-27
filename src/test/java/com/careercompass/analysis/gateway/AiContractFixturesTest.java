package com.careercompass.analysis.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.careercompass.common.api.ErrorCode;
import com.careercompass.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * CareerCompass-AI 계약 표본(AI 저장소 {@code fixtures/contract/*.json}, AI #30)을 BE 의 실제 게이트웨이로 돌린다 — 표본의
 * 요청을 {@link HttpLlmGateway} 메서드로 보내고, BE 가 응답을 계약대로 해석하는지(성공·실패 사유·장애) 본다.
 *
 * <p>살아 있는 AI 서버가 필요해 기본으로는 돌지 않는다. 스텁 모드면 키 없이 된다:
 *
 * <pre>
 * AI_CONTRACT_URL=http://127.0.0.1:18100 AI_FIXTURES_DIR=../CareerCompass-AI/fixtures/contract ./gradlew test --tests '*AiContractFixturesTest'
 * </pre>
 *
 * <p>표본 파일은 복사하지 않고 AI 저장소에서 바로 읽는다 — 한쪽이 모양을 바꾸면 이 테스트가 깨지는 것이 목적이다.
 */
@EnabledIfEnvironmentVariable(named = "AI_CONTRACT_URL", matches = ".+")
class AiContractFixturesTest {

  private final ObjectMapper mapper = new ObjectMapper();

  private HttpLlmGateway gateway() {
    return new HttpLlmGateway(
        new AiProperties(System.getenv("AI_CONTRACT_URL"), Duration.ofSeconds(30), 1, "v1", 1000),
        mapper,
        new com.careercompass.common.privacy.PrivacyMasker(),
        new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
  }

  private static Path fixturesDir() {
    String dir = System.getenv("AI_FIXTURES_DIR");
    return Path.of(dir == null || dir.isBlank() ? "../CareerCompass-AI/fixtures/contract" : dir);
  }

  @TestFactory
  Stream<DynamicTest> everyFixture() throws Exception {
    List<Path> files;
    try (Stream<Path> listed = Files.list(fixturesDir())) {
      files = listed.filter(p -> p.toString().endsWith(".json")).sorted().toList();
    }
    assertThat(files).as("표본이 없다: " + fixturesDir().toAbsolutePath()).isNotEmpty();
    HttpLlmGateway gateway = gateway();
    return files.stream()
        .map(
            file ->
                DynamicTest.dynamicTest(
                    file.getFileName().toString(),
                    () -> replay(gateway, mapper.readTree(file.toFile()))));
  }

  private void replay(HttpLlmGateway gateway, JsonNode fixture) {
    String endpoint = fixture.path("endpoint").asText();
    JsonNode request = fixture.path("request");
    JsonNode expect = fixture.path("expect");
    int status = expect.path("status").asInt(200);
    JsonNode equals = expect.path("equals");

    if (!request.isObject()) {
      return; // 장애 표본(error-llm-unavailable) — 요청이 없다. 5xx → LLM_UNAVAILABLE 은 HttpLlmGatewayTest 가
      // 지킨다
    }
    switch (endpoint) {
      case "POST /v1/parse-posting" -> {
        if (!request.has("rawContent")) {
          // 계약 위반 요청(필수 필드 누락) — BE 는 늘 rawContent 를 싣는다(null 이면 빈 문자열). 보낼 방법이 없다.
          return;
        }
        String title = request.path("title").asText();
        String raw = request.path("rawContent").asText();
        if (status == 200 && equals.path("parsingFailed").asBoolean(false)) {
          String reasonCode = equals.path("reasonCode").asText(null);
          assertThatThrownBy(() -> gateway.parsePosting(title, raw))
              .isInstanceOfSatisfying(
                  LlmGateway.ParsingFailedException.class,
                  e -> assertThat(e.reasonCode()).isEqualTo(reasonCode));
        } else if (status == 200) {
          LlmGateway.ParsedPosting parsed = gateway.parsePosting(title, raw);
          assertThat(parsed.keywords()).hasSizeGreaterThanOrEqualTo(3);
          if (equals.has("type")) {
            assertThat(parsed.type()).isEqualTo(equals.path("type").asText());
          }
        } else {
          assertThatThrownBy(() -> gateway.parsePosting(title, raw))
              .isInstanceOfSatisfying(
                  BusinessException.class,
                  e -> assertThat(e.code()).isEqualTo(ErrorCode.LLM_UNAVAILABLE));
        }
      }
      case "POST /v1/comments" -> {
        LlmGateway.Comments comments =
            gateway.comments(
                new LlmGateway.CommentContext(
                    texts(request.path("matchedKeywords")),
                    texts(request.path("missingQualifications")),
                    texts(request.path("matchedPreferences")),
                    request.path("topExperienceTitle").asText("")));
        if (equals.has("strength") && equals.path("strength").isNull()) {
          assertThat(comments.strength()).isNull();
          assertThat(comments.weakness()).isNull();
        } else {
          assertThat(comments.strength()).isNotBlank();
        }
      }
      case "POST /v1/draft-answer" -> {
        int maxChars = request.path("maxChars").asInt(0);
        String answer =
            gateway.draftAnswer(
                request.path("question").asText(),
                maxChars == 0 ? null : maxChars,
                request.path("tone").asText("formal"),
                request.path("postingTitle").asText(""),
                texts(request.path("keywords")),
                texts(request.path("experienceSummaries")));
        assertThat(answer).isNotBlank();
      }
      default -> throw new AssertionError("BE 게이트웨이가 모르는 엔드포인트: " + endpoint);
    }
  }

  private static List<String> texts(JsonNode array) {
    List<String> out = new ArrayList<>();
    array.forEach(node -> out.add(node.asText()));
    return out;
  }
}
