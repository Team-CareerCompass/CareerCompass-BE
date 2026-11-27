package com.careercompass.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.careercompass.support.ApiITBase;
import com.careercompass.support.TestFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** M6 — 초안 생성(#36)·SSE 복원(#37)·재생성/버전(#38)·편집/저장(#39)·이력(#40). */
class ApplicationFlowIT extends ApiITBase {

  @Autowired private JdbcTemplate jdbc;
  @LocalServerPort private int port;

  private long seedParsedPosting(long userId, String type, String formQuestionsJson) {
    long boardId = TestFixtures.board(jdbc, userId, "https://apps.local/" + UUID.randomUUID());
    long postingId =
        jdbc.queryForObject(
            "insert into posting (board_id, title, url, url_hash, raw_content, type)"
                + " values (?, '2026 백엔드 신입 채용', ?, ?, '백엔드 Spring 채용 5명', ?) returning id",
            Long.class,
            boardId,
            "https://apps.local/p/" + UUID.randomUUID(),
            UUID.randomUUID().toString(),
            type);
    jdbc.update(
        "insert into posting_parsed (posting_id, parse_status, keywords, form_questions)"
            + " values (?, 'done', '[\"백엔드\",\"Spring\",\"AWS\"]'::jsonb, ?::jsonb)",
        postingId,
        formQuestionsJson);
    return postingId;
  }

  private void seedCard(Session session) {
    call(
        HttpMethod.POST,
        "/experiences",
        Map.of(
            "type", "project",
            "title", "CareerCompass 백엔드",
            "startDate", "2026-01-01",
            "data", Map.of("summary", "Spring 백엔드 API 개발")),
        session.accessToken());
  }

  private static final String TWO_QUESTIONS =
      "[{\"order\":1,\"question\":\"지원 동기를 작성해 주세요\",\"maxChars\":500},"
          + "{\"order\":2,\"question\":\"본인의 강점을 기술해 주세요\",\"maxChars\":null}]";

  private JsonNode awaitFinished(Session session, long applicationId) throws Exception {
    for (int i = 0; i < 50; i++) {
      JsonNode data =
          json(call(HttpMethod.GET, "/applications/" + applicationId, null, session.accessToken()))
              .path("data");
      if (!"generating".equals(data.path("status").asText())) {
        return data;
      }
      Thread.sleep(100);
    }
    throw new AssertionError("초안 생성이 제한 시간 안에 끝나지 않았습니다");
  }

  @Test
  @DisplayName("초안 생성 — 항목별 답변이 만들어지고 글자 수 제한이 지켜진다 (#36)")
  void createGeneratesDrafts() throws Exception {
    Session session = login();
    seedCard(session);
    long postingId = seedParsedPosting(session.userId(), "recruit", TWO_QUESTIONS);

    ResponseEntity<String> created =
        call(
            HttpMethod.POST,
            "/applications",
            Map.of("postingId", postingId, "tone", "formal"),
            session.accessToken());
    assertThat(created.getStatusCode().value()).isEqualTo(201);
    long applicationId = json(created).path("data").path("id").asLong();

    JsonNode data = awaitFinished(session, applicationId);
    assertThat(data.path("status").asText()).isEqualTo("ready");
    assertThat(data.path("items")).hasSize(2);
    for (JsonNode item : data.path("items")) {
      assertThat(item.path("status").asText()).isEqualTo("done");
      assertThat(item.path("answer").asText()).isNotBlank();
    }
    assertThat(data.path("items").get(0).path("answer").asText().length()).isLessThanOrEqualTo(500);
  }

  @Test
  @DisplayName("공모전 공고의 생성 요청은 거절된다 — 장학금·채용만 (#36, F3-3)")
  void contestRejected() {
    Session session = login();
    long postingId = seedParsedPosting(session.userId(), "contest", TWO_QUESTIONS);

    ResponseEntity<String> res =
        call(
            HttpMethod.POST,
            "/applications",
            Map.of("postingId", postingId),
            session.accessToken());

    assertThat(res.getStatusCode().value()).isEqualTo(400);
    assertThat(json(res).path("error").path("field").asText()).isEqualTo("postingId");
  }

  @Test
  @DisplayName("양식 미인식 — 빈 items 로 시작하고 직접 추가하면 즉시 초안이 붙는다 (#36 계약)")
  void manualItemPath() {
    Session session = login();
    seedCard(session);
    long postingId = seedParsedPosting(session.userId(), "scholarship", "null");

    JsonNode created =
        json(call(
                HttpMethod.POST,
                "/applications",
                Map.of("postingId", postingId),
                session.accessToken()))
            .path("data");
    assertThat(created.path("items")).isEmpty();
    assertThat(created.path("status").asText()).isEqualTo("ready");

    JsonNode item =
        json(call(
                HttpMethod.POST,
                "/applications/" + created.path("id").asLong() + "/items",
                Map.of("question", "지원 동기를 작성해 주세요", "maxChars", 300),
                session.accessToken()))
            .path("data");
    assertThat(item.path("status").asText()).isEqualTo("done");
    assertThat(item.path("answer").asText()).isNotBlank();
  }

  @Test
  @DisplayName("재생성 4회 → 버전은 3개 유지, 되돌리기는 현재 본문도 보관한다 (#38)")
  void regenerateAndVersions() throws Exception {
    Session session = login();
    seedCard(session);
    long postingId = seedParsedPosting(session.userId(), "recruit", TWO_QUESTIONS);
    long applicationId =
        json(call(
                HttpMethod.POST,
                "/applications",
                Map.of("postingId", postingId),
                session.accessToken()))
            .path("data")
            .path("id")
            .asLong();
    JsonNode data = awaitFinished(session, applicationId);
    long itemId = data.path("items").get(0).path("id").asLong();
    String base = "/applications/" + applicationId + "/items/" + itemId;

    for (int i = 0; i < 4; i++) {
      ResponseEntity<String> res =
          call(
              HttpMethod.POST,
              base + "/regenerate",
              Map.of("tone", "casual"),
              session.accessToken());
      assertThat(res.getStatusCode().value()).isEqualTo(200);
    }
    JsonNode versions =
        json(call(HttpMethod.GET, base + "/versions", null, session.accessToken())).path("data");
    assertThat(versions).hasSize(3);

    // 되돌리기 — 대상 버전의 본문으로 교체된다
    long versionId = versions.get(0).path("id").asLong();
    String versionAnswer = versions.get(0).path("answer").asText();
    JsonNode reverted =
        json(call(
                HttpMethod.POST,
                base + "/versions/" + versionId + "/revert",
                null,
                session.accessToken()))
            .path("data");
    assertThat(reverted.path("answer").asText()).isEqualTo(versionAnswer);

    // 알 수 없는 tone 은 400
    ResponseEntity<String> badTone =
        call(HttpMethod.POST, base + "/regenerate", Map.of("tone", "angry"), session.accessToken());
    assertThat(badTone.getStatusCode().value()).isEqualTo(400);
  }

  @Test
  @DisplayName("글자 수 초과 저장 허용 + 최종 저장 → 스냅샷·이력 → 결과 입력 → 삭제 (#39 #40)")
  void editSaveHistoryFlow() throws Exception {
    Session session = login();
    seedCard(session);
    long postingId = seedParsedPosting(session.userId(), "recruit", TWO_QUESTIONS);
    jdbc.update(
        "insert into suitability_score (user_id, posting_id, score, label, breakdown)"
            + " values (?, ?, 77, 'suitable', '[]'::jsonb)",
        session.userId(),
        postingId);
    long applicationId =
        json(call(
                HttpMethod.POST,
                "/applications",
                Map.of("postingId", postingId),
                session.accessToken()))
            .path("data")
            .path("id")
            .asLong();
    JsonNode data = awaitFinished(session, applicationId);
    long itemId = data.path("items").get(0).path("id").asLong();

    // 글자 수 초과(maxChars 500 에 1000자) 저장 — 서버는 막지 않는다
    String longAnswer = "가".repeat(1000);
    JsonNode edited =
        json(call(
                HttpMethod.PATCH,
                "/applications/" + applicationId + "/items/" + itemId,
                Map.of("answer", longAnswer),
                session.accessToken()))
            .path("data");
    assertThat(edited.path("answer").asText()).hasSize(1000);

    JsonNode saved =
        json(call(
                HttpMethod.POST,
                "/applications/" + applicationId + "/save",
                null,
                session.accessToken()))
            .path("data");
    assertThat(saved.path("status").asText()).isEqualTo("saved");
    assertThat(saved.path("scoreSnapshot").asInt()).isEqualTo(77);

    JsonNode listed =
        json(call(HttpMethod.GET, "/applications?status=saved", null, session.accessToken()))
            .path("data")
            .path("applications");
    assertThat(listed).hasSize(1);
    assertThat(listed.get(0).path("postingTitle").asText()).contains("백엔드");
    assertThat(listed.get(0).path("result").asText()).isEqualTo("none");

    JsonNode resulted =
        json(call(
                HttpMethod.PATCH,
                "/applications/" + applicationId + "/result",
                Map.of("result", "pass"),
                session.accessToken()))
            .path("data");
    assertThat(resulted.path("result").asText()).isEqualTo("pass");

    call(HttpMethod.DELETE, "/applications/" + applicationId, null, session.accessToken());
    assertThat(
            json(call(HttpMethod.GET, "/applications", null, session.accessToken()))
                .path("data")
                .path("applications"))
        .isEmpty();
    Integer itemRows =
        jdbc.queryForObject(
            "select count(*) from application_item where application_id = ?",
            Integer.class,
            applicationId);
    assertThat(itemRows).isZero();
  }

  @Test
  @DisplayName("SSE 재연결 복원 — 생성 종료 후 구독해도 스냅샷과 complete 를 받는다 (#37)")
  void sseSnapshotRestore() throws Exception {
    Session session = login();
    seedCard(session);
    long postingId = seedParsedPosting(session.userId(), "recruit", TWO_QUESTIONS);
    long applicationId =
        json(call(
                HttpMethod.POST,
                "/applications",
                Map.of("postingId", postingId),
                session.accessToken()))
            .path("data")
            .path("id")
            .asLong();
    awaitFinished(session, applicationId);

    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:"
                        + port
                        + "/api/v1/applications/"
                        + applicationId
                        + "/stream"))
            .header("Authorization", "Bearer " + session.accessToken())
            .header("Accept", "text/event-stream")
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build();
    HttpResponse<java.io.InputStream> response =
        client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    assertThat(response.statusCode()).isEqualTo(200);

    StringBuilder received = new StringBuilder();
    // 스트림이 **깨끗하게 끝날 때까지** 읽는다. 앱(FE)은 연결 종료로 스트림 끝을 판단하고 EOF 예외를 「중단」으로
    // 읽는다 — 예전 이 자리는 그 예외를 잡음으로 무시해, 끝날 때의 ASYNC 디스패치 인가 실패를 놓쳤다.
    try (var reader =
        new java.io.BufferedReader(
            new java.io.InputStreamReader(
                response.body(), java.nio.charset.StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        received.append(line).append('\n');
      }
    }
    assertThat(received.toString()).contains("event:item_done");
    assertThat(received.toString()).contains("event:complete");
    // 생성 중에 끝난 경우와 같은 모양 — complete{status} (#59)
    assertThat(received.toString()).contains("\"status\":\"ready\"");
  }

  private ResponseEntity<String> createApplication(Session session, Map<String, Object> body) {
    return call(HttpMethod.POST, "/applications", body, session.accessToken());
  }

  private int applicationRows(long userId, long postingId) {
    return jdbc.queryForObject(
        "select count(*) from application where user_id = ? and posting_id = ?",
        Integer.class,
        userId,
        postingId);
  }

  @Test
  @DisplayName("같은 공고로 두 번 호출하면 지원서는 하나 — 두 번째는 200·같은 id, 끝난 답변을 잃지 않는다 (#57)")
  void createIsIdempotentPerPosting() throws Exception {
    Session session = login();
    seedCard(session);
    long postingId = seedParsedPosting(session.userId(), "recruit", TWO_QUESTIONS);

    ResponseEntity<String> first = createApplication(session, Map.of("postingId", postingId));
    assertThat(first.getStatusCode().value()).isEqualTo(201);
    long id = json(first).path("data").path("id").asLong();
    awaitFinished(session, id);

    ResponseEntity<String> again =
        createApplication(session, Map.of("postingId", postingId, "tone", "casual"));
    assertThat(again.getStatusCode().value()).isEqualTo(200);
    JsonNode data = json(again).path("data");
    assertThat(data.path("id").asLong()).isEqualTo(id);
    assertThat(data.path("tone").asText()).isEqualTo("formal"); // 재사용 시 요청의 tone 은 무시
    assertThat(data.path("items")).hasSize(2);
    for (JsonNode item : data.path("items")) {
      assertThat(item.path("answer").asText()).isNotBlank();
    }
    assertThat(applicationRows(session.userId(), postingId)).isEqualTo(1);
  }

  @Test
  @DisplayName("동시에 여러 번 호출해도 진행 중 지원서는 하나만 생긴다 (#57)")
  void concurrentCreateMakesOne() throws Exception {
    Session session = login();
    seedCard(session);
    long postingId = seedParsedPosting(session.userId(), "recruit", TWO_QUESTIONS);

    var pool = java.util.concurrent.Executors.newFixedThreadPool(6);
    try {
      var futures =
          java.util.stream.IntStream.range(0, 6)
              .mapToObj(
                  i ->
                      pool.submit(() -> createApplication(session, Map.of("postingId", postingId))))
              .toList();
      java.util.Set<Long> ids = new java.util.HashSet<>();
      int created = 0;
      for (var future : futures) {
        ResponseEntity<String> res = future.get();
        assertThat(res.getStatusCode().value()).isIn(200, 201);
        created += res.getStatusCode().value() == 201 ? 1 : 0;
        ids.add(json(res).path("data").path("id").asLong());
      }
      assertThat(created).isEqualTo(1);
      assertThat(ids).hasSize(1);
    } finally {
      pool.shutdown();
    }
    assertThat(applicationRows(session.userId(), postingId)).isEqualTo(1);
  }

  @Test
  @DisplayName("저장본을 다시 고치면 ready 로 돌아간다 — /restore 별칭도 같은 동작 (FE canon)")
  void editingSavedReopens() throws Exception {
    Session session = login();
    seedCard(session);
    long postingId = seedParsedPosting(session.userId(), "recruit", TWO_QUESTIONS);
    long id =
        json(createApplication(session, Map.of("postingId", postingId)))
            .path("data")
            .path("id")
            .asLong();
    JsonNode data = awaitFinished(session, id);
    long itemId = data.path("items").get(0).path("id").asLong();
    String base = "/applications/" + id;
    // 재생성 한 번 — 이전 답이 버전으로 남는다(되돌리기 대상)
    call(HttpMethod.POST, base + "/items/" + itemId + "/regenerate", null, session.accessToken());
    call(HttpMethod.POST, base + "/save", null, session.accessToken());

    call(
        HttpMethod.PATCH,
        base + "/items/" + itemId,
        Map.of("answer", "저장 뒤 고친 답"),
        session.accessToken());
    JsonNode reopened = json(call(HttpMethod.GET, base, null, session.accessToken())).path("data");
    assertThat(reopened.path("status").asText()).isEqualTo("ready");

    long versionId =
        json(call(
                HttpMethod.GET,
                base + "/items/" + itemId + "/versions",
                null,
                session.accessToken()))
            .path("data")
            .get(0)
            .path("id")
            .asLong();
    assertThat(versionId).isPositive();
    ResponseEntity<String> restored =
        call(
            HttpMethod.POST,
            base + "/items/" + itemId + "/versions/" + versionId + "/restore",
            null,
            session.accessToken());
    assertThat(restored.getStatusCode().value()).isEqualTo(200);
  }

  @Test
  @DisplayName("saved 만 있으면 같은 공고에 새 지원서를 만든다 — 201·새 id (#57)")
  void createAfterSaveMakesNew() throws Exception {
    Session session = login();
    seedCard(session);
    long postingId = seedParsedPosting(session.userId(), "recruit", TWO_QUESTIONS);
    long first =
        json(createApplication(session, Map.of("postingId", postingId)))
            .path("data")
            .path("id")
            .asLong();
    awaitFinished(session, first);
    call(HttpMethod.POST, "/applications/" + first + "/save", null, session.accessToken());

    ResponseEntity<String> second = createApplication(session, Map.of("postingId", postingId));
    assertThat(second.getStatusCode().value()).isEqualTo(201);
    assertThat(json(second).path("data").path("id").asLong()).isNotEqualTo(first);
  }

  @Test
  @DisplayName("items 를 실으면 그 문항 그대로 만든다 — order 정렬, maxChars null 유지 (#58)")
  void createWithEditedItems() throws Exception {
    Session session = login();
    seedCard(session);
    long postingId = seedParsedPosting(session.userId(), "recruit", TWO_QUESTIONS);

    java.util.List<Map<String, Object>> items = new java.util.ArrayList<>();
    items.add(new java.util.HashMap<>(Map.of("order", 2, "question", "입사 후 포부", "maxChars", 300)));
    java.util.Map<String, Object> noLimit = new java.util.HashMap<>();
    noLimit.put("order", 1);
    noLimit.put("question", "직접 쓴 문항 — 협업 경험");
    noLimit.put("maxChars", null);
    items.add(noLimit);

    ResponseEntity<String> res =
        createApplication(session, Map.of("postingId", postingId, "items", items));
    assertThat(res.getStatusCode().value()).isEqualTo(201);
    JsonNode data = awaitFinished(session, json(res).path("data").path("id").asLong());
    assertThat(data.path("items")).hasSize(2);
    assertThat(data.path("items").get(0).path("question").asText()).isEqualTo("직접 쓴 문항 — 협업 경험");
    // 제한 없는 문항은 응답에서 서버 기본 상한(600)으로 — FE 는 양수만 받는다. 저장값은 null 로 남는다.
    assertThat(data.path("items").get(0).path("maxChars").asInt()).isEqualTo(600);
    assertThat(
            jdbc.queryForObject(
                "select max_chars from application_item where id = ?",
                Integer.class,
                data.path("items").get(0).path("id").asLong()))
        .isNull();
    assertThat(data.path("items").get(1).path("question").asText()).isEqualTo("입사 후 포부");
    assertThat(data.path("items").get(1).path("maxChars").asInt()).isEqualTo(300);
    assertThat(data.path("items").get(1).path("answer").asText().length()).isLessThanOrEqualTo(300);
  }

  @Test
  @DisplayName("items 상한·빈 문항은 INVALID_INPUT 과 필드 경로로 거절된다 (#58)")
  void createWithInvalidItemsRejected() {
    Session session = login();
    long postingId = seedParsedPosting(session.userId(), "recruit", TWO_QUESTIONS);

    java.util.List<Map<String, Object>> tooMany =
        java.util.stream.IntStream.range(0, 21)
            .mapToObj(i -> Map.<String, Object>of("question", "문항 " + i))
            .toList();
    ResponseEntity<String> overLimit =
        createApplication(session, Map.of("postingId", postingId, "items", tooMany));
    assertThat(overLimit.getStatusCode().value()).isEqualTo(400);
    assertThat(json(overLimit).path("error").path("field").asText()).isEqualTo("items");

    ResponseEntity<String> blank =
        createApplication(
            session,
            Map.of("postingId", postingId, "items", java.util.List.of(Map.of("question", " "))));
    assertThat(blank.getStatusCode().value()).isEqualTo(400);
    assertThat(json(blank).path("error").path("field").asText()).isEqualTo("items[0].question");
    assertThat(applicationRows(session.userId(), postingId)).isZero();
  }

  @Test
  @DisplayName("남의 지원서 접근·스트림 구독은 404")
  void othersApplicationHidden() {
    Session owner = login();
    seedCard(owner);
    long postingId = seedParsedPosting(owner.userId(), "recruit", TWO_QUESTIONS);
    long applicationId =
        json(call(
                HttpMethod.POST,
                "/applications",
                Map.of("postingId", postingId),
                owner.accessToken()))
            .path("data")
            .path("id")
            .asLong();

    Session stranger = login();
    assertThat(
            call(HttpMethod.GET, "/applications/" + applicationId, null, stranger.accessToken())
                .getStatusCode()
                .value())
        .isEqualTo(404);
    assertThat(
            call(
                    HttpMethod.GET,
                    "/applications/" + applicationId + "/stream",
                    null,
                    stranger.accessToken())
                .getStatusCode()
                .value())
        .isEqualTo(404);
  }
}
