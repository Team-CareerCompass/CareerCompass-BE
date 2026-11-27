package com.careercompass.analysis.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.careercompass.common.api.ErrorCode;
import com.careercompass.common.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HttpLlmGatewayTest {

  private HttpServer server;

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  private HttpLlmGateway gateway(int port) {
    return new HttpLlmGateway(
        new AiProperties("http://127.0.0.1:" + port, Duration.ofSeconds(2), 1, "v1", 100),
        new ObjectMapper(),
        new com.careercompass.common.privacy.PrivacyMasker(),
        new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
  }

  @Test
  @DisplayName("정상 응답 파싱 + 동일 입력은 캐시되어 업스트림을 한 번만 부른다 (#35)")
  void parsesAndCaches() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/parse-posting",
        exchange -> {
          calls.incrementAndGet();
          byte[] body =
              """
              {"type":"recruit","keywords":["백엔드","Spring","AWS"],"preferences":["우대"],
               "dueDate":"2026-12-31","formQuestions":[{"order":1,"question":"지원 동기","maxChars":500}],
               "usage":{"totalTokens":123}}
              """
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
          }
        });
    server.start();
    HttpLlmGateway gateway = gateway(server.getAddress().getPort());

    LlmGateway.ParsedPosting first = gateway.parsePosting("채용", "본문");
    LlmGateway.ParsedPosting second = gateway.parsePosting("채용", "본문");

    assertThat(first.keywords()).containsExactly("백엔드", "Spring", "AWS");
    assertThat(first.formQuestions()).hasSize(1);
    assertThat(second).isEqualTo(first);
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  @DisplayName("AI 서버 5xx 지속 → 재시도 후 LLM_UNAVAILABLE(503) 로 변환 (#35)")
  void serverFailureMapsTo503() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/parse-posting",
        exchange -> {
          exchange.sendResponseHeaders(500, -1);
          exchange.close();
        });
    server.start();
    HttpLlmGateway gateway = gateway(server.getAddress().getPort());

    assertThatThrownBy(() -> gateway.parsePosting("채용", "본문"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.LLM_UNAVAILABLE));
  }

  @Test
  @DisplayName("AI 가 parsingFailed 를 주면 ParsingFailedException — 503 이 아니라 failed 확정")
  void parsingFailedFlag() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/parse-posting",
        exchange -> {
          byte[] body =
              "{\"parsingFailed\":true,\"reason\":\"키워드 부족\"}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
          }
        });
    server.start();
    HttpLlmGateway gateway = gateway(server.getAddress().getPort());

    assertThatThrownBy(() -> gateway.parsePosting("채용", "본문"))
        .isInstanceOf(LlmGateway.ParsingFailedException.class);
  }

  private void serveJson(String path, int status, String json, AtomicInteger calls) {
    server.createContext(
        path,
        exchange -> {
          calls.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          byte[] body = json.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, body.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
          }
        });
  }

  @Test
  @DisplayName("AI 호출은 HTTP/1.1 — h2c 업그레이드 헤더를 붙이지 않고 본문을 그대로 보낸다 (uvicorn 은 업그레이드 시 본문을 잃는다)")
  void noH2cUpgrade() throws Exception {
    java.util.concurrent.atomic.AtomicReference<String> upgrade =
        new java.util.concurrent.atomic.AtomicReference<>("unset");
    java.util.concurrent.atomic.AtomicReference<String> body =
        new java.util.concurrent.atomic.AtomicReference<>();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/comments",
        exchange -> {
          upgrade.set(exchange.getRequestHeaders().getFirst("Upgrade"));
          body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] out = "{\"strength\":\"s\",\"weakness\":\"w\"}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, out.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(out);
          }
        });
    server.start();
    HttpLlmGateway gateway = gateway(server.getAddress().getPort());

    gateway.comments(
        new LlmGateway.CommentContext(
            java.util.List.of("Spring"), java.util.List.of(), java.util.List.of(), null));

    assertThat(upgrade.get()).isNull();
    assertThat(body.get()).contains("\"matchedKeywords\":[\"Spring\"]");
  }

  @Test
  @DisplayName(
      "본문을 못 가져온 공고(rawContent=null)도 빈 문자열로 보내 parsingFailed 로 끝난다 — NPE 로 pending 에 갇히지 않는다")
  void nullRawContentIsSentAsEmpty() throws Exception {
    java.util.concurrent.atomic.AtomicReference<String> body =
        new java.util.concurrent.atomic.AtomicReference<>();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/parse-posting",
        exchange -> {
          body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] out =
              "{\"parsingFailed\":true,\"reason\":\"본문이 비어 있습니다\",\"reasonCode\":\"EMPTY\"}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, out.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(out);
          }
        });
    server.start();
    HttpLlmGateway gateway = gateway(server.getAddress().getPort());

    assertThatThrownBy(() -> gateway.parsePosting("제목", null))
        .isInstanceOfSatisfying(
            LlmGateway.ParsingFailedException.class,
            e -> assertThat(e.reasonCode()).isEqualTo("EMPTY"));
    assertThat(body.get()).contains("\"rawContent\":\"\"");
  }

  @Test
  @DisplayName("parsingFailed 의 reasonCode 를 싣는다 — NOT_A_POSTING 은 목록 제외 대상 (#65)")
  void reasonCodeCarried() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    serveJson(
        "/v1/parse-posting",
        200,
        "{\"parsingFailed\":true,\"reason\":\"학사 공지\",\"reasonCode\":\"NOT_A_POSTING\"}",
        new AtomicInteger());
    server.start();
    HttpLlmGateway gateway = gateway(server.getAddress().getPort());

    assertThatThrownBy(() -> gateway.parsePosting("2026-2 수강신청 안내", "본문"))
        .isInstanceOfSatisfying(
            LlmGateway.ParsingFailedException.class,
            e -> {
              assertThat(e.reasonCode()).isEqualTo("NOT_A_POSTING");
              assertThat(e.notAPosting()).isTrue();
            });
  }

  @Test
  @DisplayName("분야 유사도 — 20건씩 나눠 부르고 id 별 점수를 모은다 (AI 계약 §5)")
  void fieldScoresBatched() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    java.util.List<String> bodies = new java.util.concurrent.CopyOnWriteArrayList<>();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/field-similarity",
        exchange -> {
          calls.incrementAndGet();
          String request =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          bodies.add(request);
          // 요청에 실린 id 를 그대로 돌려준다 — 점수는 id 마다 다르게
          var node = new ObjectMapper().readTree(request);
          StringBuilder scores = new StringBuilder();
          node.path("postings")
              .forEach(
                  p -> {
                    if (scores.length() > 0) {
                      scores.append(',');
                    }
                    long id = p.path("id").asLong();
                    scores
                        .append("{\"id\":")
                        .append(id)
                        .append(",\"score\":")
                        .append(id % 101)
                        .append('}');
                  });
          byte[] body =
              ("{\"scores\":["
                      + scores
                      + "],\"method\":\"embedding\",\"usage\":{\"totalTokens\":0}}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
          }
        });
    server.start();
    HttpLlmGateway gateway = gateway(server.getAddress().getPort());

    java.util.List<LlmGateway.FieldPosting> postings =
        java.util.stream.LongStream.rangeClosed(1, 25)
            .mapToObj(
                id -> new LlmGateway.FieldPosting(id, "공고 " + id, java.util.List.of("Spring")))
            .toList();
    java.util.Map<Long, Integer> scores =
        gateway.fieldScores(java.util.List.of("백엔드", " ", "Spring"), postings);

    assertThat(calls.get()).isEqualTo(2);
    assertThat(scores).hasSize(25).containsEntry(7L, 7).containsEntry(25L, 25);
    assertThat(bodies.get(0)).contains("\"interestTexts\":[\"백엔드\",\"Spring\"]"); // 공백 텍스트는 뺀다
  }

  @Test
  @DisplayName("분야 유사도 실패·관심 분야 없음 — 던지지 않고 빈 결과(글자 비교로 대신) (AI 계약 §5)")
  void fieldScoresFallBack() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    serveJson(
        "/v1/field-similarity", 503, "{\"error\":\"budget\",\"code\":\"LLM_UNAVAILABLE\"}", calls);
    server.start();
    HttpLlmGateway gateway = gateway(server.getAddress().getPort());
    var postings = java.util.List.of(new LlmGateway.FieldPosting(1L, "공고", java.util.List.of()));

    assertThat(gateway.fieldScores(java.util.List.of("백엔드"), postings)).isEmpty();
    int afterFailure = calls.get();
    assertThat(afterFailure).isGreaterThanOrEqualTo(1);

    assertThat(gateway.fieldScores(java.util.List.of(" "), postings)).isEmpty();
    assertThat(calls.get()).isEqualTo(afterFailure); // 관심 분야가 비면 부르지도 않는다
  }

  @Test
  @DisplayName("게이트웨이 선택 — 주소가 비면 휴리스틱, 있으면 HTTP 하나만 (#64)")
  void gatewaySelection() {
    LlmGatewayConfig config = new LlmGatewayConfig();
    var masker = new com.careercompass.common.privacy.PrivacyMasker();
    var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

    assertThat(
            config.llmGateway(
                new AiProperties("", null, 0, null, 0), new ObjectMapper(), masker, registry))
        .isInstanceOf(HeuristicLlmGateway.class);
    assertThat(
            config.llmGateway(
                new AiProperties(null, null, 0, null, 0), new ObjectMapper(), masker, registry))
        .isInstanceOf(HeuristicLlmGateway.class);
    assertThat(
            config.llmGateway(
                new AiProperties("http://ai:8000", null, 0, null, 0),
                new ObjectMapper(),
                masker,
                registry))
        .isInstanceOf(HttpLlmGateway.class);
  }
}
