package com.careercompass.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.careercompass.support.ApiITBase;
import com.careercompass.support.TestFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * #50 — FE `ApiWireContractSmokeTest` 의 서버측 대응. 전 엔드포인트의 route·상태 코드·응답 필드 이름(널 허용 필드 포함)을 대조한다.
 * 스펙이 바뀌면 이 테스트와 docs/API_SPEC_v0.1.md 를 함께 고치고 FE 저장소에 이슈로 알린다 — 조용히 바꾸지 않는다.
 */
class ContractSmokeIT extends ApiITBase {

  @Autowired private JdbcTemplate jdbc;

  private void assertOk(ResponseEntity<String> res, int expectedStatus) {
    assertThat(res.getStatusCode().value()).isEqualTo(expectedStatus);
    assertThat(res.getHeaders().getContentType().toString()).contains("application/json");
    assertThat(json(res).path("ok").isBoolean()).isTrue();
  }

  private void assertHas(JsonNode node, String... fields) {
    for (String field : fields) {
      assertThat(node.has(field)).as("응답에 %s 필드가 있어야 한다 (FE DTO 대조)", field).isTrue();
    }
  }

  @Test
  @DisplayName("§1 인증 — 로그인·재발급·로그아웃·지문 등록 계약")
  void authContract() {
    ResponseEntity<String> login =
        call(
            HttpMethod.POST,
            "/auth/social/kakao",
            Map.of("accessToken", "tok-" + UUID.randomUUID(), "deviceId", "smoke-dev"),
            null);
    assertOk(login, 201);
    JsonNode data = json(login).path("data");
    // FE 필수: accessToken·refreshToken·isNewUser·expiresIn(초, > 0). user 는 하위 호환
    assertHas(data, "accessToken", "refreshToken", "isNewUser", "expiresIn", "user");
    assertThat(data.path("isNewUser").asBoolean()).isTrue();
    assertThat(data.path("expiresIn").asLong()).isPositive();
    assertHas(data.path("user"), "id", "email", "name", "onboardingDone");

    ResponseEntity<String> refresh =
        call(
            HttpMethod.POST,
            "/auth/refresh",
            Map.of("refreshToken", data.path("refreshToken").asText()),
            null);
    assertOk(refresh, 200);
    JsonNode refreshed = json(refresh).path("data");
    assertHas(refreshed, "accessToken", "refreshToken", "expiresIn");
    // 같은 초에 재발급해도 새 access 는 이전과 달라야 한다 — FE 는 같으면 세션을 지운다
    assertThat(refreshed.path("accessToken").asText())
        .isNotEqualTo(data.path("accessToken").asText());

    String access = refreshed.path("accessToken").asText();
    assertOk(
        call(HttpMethod.POST, "/auth/biometric/register", Map.of("deviceId", "smoke-dev"), access),
        200);
    // FE 의 로그아웃 본문은 {refreshToken}
    assertOk(
        call(
            HttpMethod.POST,
            "/auth/logout",
            Map.of("refreshToken", refreshed.path("refreshToken").asText()),
            access),
        200);
    assertThat(
            call(
                    HttpMethod.POST,
                    "/auth/refresh",
                    Map.of("refreshToken", refreshed.path("refreshToken").asText()),
                    null)
                .getStatusCode()
                .value())
        .isEqualTo(401);
  }

  @Test
  @DisplayName("§2 프로필·메타 — me/직무/태그/동의/학교·직무 목록 계약")
  void profileContract() {
    Session s = login();

    ResponseEntity<String> me = call(HttpMethod.GET, "/users/me", null, s.accessToken());
    assertOk(me, 200);
    assertHas(
        json(me).path("data"),
        "id",
        "name",
        "school",
        "department",
        "gpa",
        "gradYear",
        "jobInterests",
        "tags",
        "onboardingDone",
        "completion");

    assertOk(call(HttpMethod.PATCH, "/users/me", Map.of("school", "건국대학교"), s.accessToken()), 200);
    assertOk(
        call(
            HttpMethod.PUT,
            "/users/me/job-interests",
            Map.of("interests", List.of(Map.of("code", "backend", "priority", 1))),
            s.accessToken()),
        200);
    assertOk(
        call(HttpMethod.PUT, "/users/me/tags", Map.of("tags", List.of("AI")), s.accessToken()),
        200);
    assertOk(
        call(
            HttpMethod.PUT, "/users/me/consent", Map.of("modelImprovement", true), s.accessToken()),
        200);
    ResponseEntity<String> schools =
        call(HttpMethod.GET, "/meta/schools?q=건국", null, s.accessToken());
    assertOk(schools, 200);
    assertHas(json(schools).path("data"), "schools");
    ResponseEntity<String> categories =
        call(HttpMethod.GET, "/meta/job-categories", null, s.accessToken());
    assertOk(categories, 200);
    assertHas(json(categories).path("data"), "categories");
  }

  @Test
  @DisplayName("§3·§4 경험 카드·과거 지원서 계약")
  void experienceAndPastApplicationContract() {
    Session s = login();

    ResponseEntity<String> created =
        call(
            HttpMethod.POST,
            "/experiences",
            Map.of("type", "cert", "title", "SQLD", "data", Map.of("certName", "SQLD")),
            s.accessToken());
    assertOk(created, 201);
    long cardId = json(created).path("data").path("id").asLong();

    ResponseEntity<String> list = call(HttpMethod.GET, "/experiences", null, s.accessToken());
    assertOk(list, 200);
    assertHas(json(list).path("data"), "experiences", "nextCursor");
    assertHas(
        json(list).path("data").path("experiences").get(0),
        "id",
        "type",
        "title",
        "startDate",
        "endDate",
        "data",
        "createdAt");

    assertOk(
        call(
            HttpMethod.PATCH,
            "/experiences/" + cardId,
            Map.of("title", "SQLD 자격증"),
            s.accessToken()),
        200);
    assertOk(call(HttpMethod.DELETE, "/experiences/" + cardId, null, s.accessToken()), 200);

    ResponseEntity<String> uploaded =
        upload(
            "/past-applications/upload",
            "smoke.txt",
            "제가 지원하게 된 이유는 명확합니다. 지원 동기는 성장입니다.".getBytes(StandardCharsets.UTF_8),
            "스모크",
            s.accessToken());
    assertOk(uploaded, 201);
    JsonNode uploadData = json(uploaded).path("data");
    assertHas(
        uploadData, "id", "label", "fileName", "format", "filePath", "extractStatus", "items");
    assertHas(uploadData.path("items").get(0), "id", "category", "content", "confident");
    long appId = uploadData.path("id").asLong();
    long itemId = uploadData.path("items").get(0).path("id").asLong();

    ResponseEntity<String> pastList =
        call(HttpMethod.GET, "/past-applications", null, s.accessToken());
    assertOk(pastList, 200);
    assertHas(json(pastList).path("data"), "applications"); // FE: {applications: [...]}
    assertOk(
        call(
            HttpMethod.PATCH,
            "/past-applications/" + appId + "/items/" + itemId,
            Map.of("category", "motivation"),
            s.accessToken()),
        200);
    assertOk(call(HttpMethod.DELETE, "/past-applications/" + appId, null, s.accessToken()), 200);
  }

  @Test
  @DisplayName("§5 게시판·공고 — 목록 카드의 널 허용 필드까지 이름 그대로 내려간다")
  void boardAndPostingContract() {
    Session s = login();
    long boardId = TestFixtures.board(jdbc, s.userId(), "https://smoke.local/" + UUID.randomUUID());
    long postingId =
        jdbc.queryForObject(
            "insert into posting (board_id, title, url, url_hash, raw_content, type, due_date)"
                + " values (?, '스모크 채용', ?, ?, '본문', 'recruit', ?) returning id",
            Long.class,
            boardId,
            "https://smoke.local/p/" + UUID.randomUUID(),
            UUID.randomUUID().toString(),
            LocalDate.now().plusDays(5));
    jdbc.update(
        "insert into posting_parsed (posting_id, parse_status, keywords) values (?, 'done',"
            + " '[\"백엔드\"]'::jsonb)",
        postingId);
    jdbc.update(
        "insert into suitability_score (user_id, posting_id, score, label, breakdown)"
            + " values (?, ?, 88, 'very_suitable', '[]'::jsonb)",
        s.userId(),
        postingId);

    // 감지 실패도 200 + detectStatus (FE 는 상태로 화면을 가른다)
    ResponseEntity<String> detect =
        call(
            HttpMethod.POST,
            "/boards/detect",
            Map.of("url", "http://127.0.0.1:1/x"),
            s.accessToken());
    assertOk(detect, 200);
    assertHas(json(detect).path("data"), "detectStatus", "selectors", "preview");

    ResponseEntity<String> boards = call(HttpMethod.GET, "/boards", null, s.accessToken());
    assertOk(boards, 200);
    assertHas(json(boards).path("data"), "boards"); // FE: {boards: [...]}
    assertHas(
        json(boards).path("data").path("boards").get(0),
        "id",
        "url",
        "name",
        "type",
        "cycleHours",
        "status",
        "isActive",
        "failCount",
        "lastCollectedAt");
    assertOk(
        call(HttpMethod.PATCH, "/boards/" + boardId, Map.of("isActive", false), s.accessToken()),
        200);

    ResponseEntity<String> postings = call(HttpMethod.GET, "/postings", null, s.accessToken());
    assertOk(postings, 200);
    JsonNode cards = json(postings).path("data");
    assertHas(cards, "postings", "nextCursor");
    assertHas(
        cards.path("postings").get(0),
        "id",
        "title",
        "type",
        "board",
        "dueDate",
        "collectedAt",
        "parseStatus",
        "score",
        "scoreLabel",
        "scoreUnavailableReason",
        "isRead",
        "isBookmarked");
    assertHas(cards.path("postings").get(0).path("board"), "id", "name");

    // 같은 유형 공고를 하나 더 — similar 원소 모양을 보기 위해
    long sibling =
        jdbc.queryForObject(
            "insert into posting (board_id, title, url, url_hash, type)"
                + " values (?, '스모크 채용 2', ?, ?, 'recruit') returning id",
            Long.class,
            boardId,
            "https://smoke.local/p/" + UUID.randomUUID(),
            UUID.randomUUID().toString());
    jdbc.update(
        "insert into posting_parsed (posting_id, parse_status, keywords) values (?, 'done',"
            + " '[\"백엔드\"]'::jsonb)",
        sibling);

    ResponseEntity<String> detail =
        call(HttpMethod.GET, "/postings/" + postingId, null, s.accessToken());
    assertOk(detail, 200);
    JsonNode detailData = json(detail).path("data");
    // FE 필수: 목록 필드(type·board·dueDate·collectedAt·isRead·isBookmarked) + rawContent·url·similar
    assertHas(
        detailData,
        "id",
        "title",
        "type",
        "board",
        "dueDate",
        "collectedAt",
        "score",
        "scoreLabel",
        "rawContent",
        "url",
        "parsed",
        "suitability",
        "scoreUnavailableReason",
        "isRead",
        "isBookmarked",
        "canGenerateApplication",
        "similar");
    assertHas(detailData.path("board"), "id", "name");
    assertHas(
        detailData.path("parsed"), "keywords", "qualifications", "preferences", "formQuestions");
    assertHas(detailData.path("parsed").path("qualifications"), "year", "gpa");
    assertThat(detailData.path("similar")).hasSize(1);
    assertHas(
        detailData.path("similar").get(0),
        "id",
        "title",
        "type",
        "board",
        "dueDate",
        "collectedAt",
        "score",
        "scoreLabel",
        "isRead",
        "isBookmarked");
    assertHas(
        json(detail).path("data").path("suitability"),
        "score",
        "label",
        "breakdown",
        "strengthComment",
        "weaknessComment");

    assertOk(call(HttpMethod.POST, "/postings/" + postingId + "/read", null, s.accessToken()), 200);
    assertOk(
        call(HttpMethod.POST, "/postings/" + postingId + "/bookmark", null, s.accessToken()), 200);
    assertOk(
        call(HttpMethod.DELETE, "/postings/" + postingId + "/bookmark", null, s.accessToken()),
        200);
  }

  @Test
  @DisplayName("§6 지원서 — 생성부터 이력까지 계약")
  void applicationContract() throws Exception {
    Session s = login();
    call(
        HttpMethod.POST,
        "/experiences",
        Map.of("type", "cert", "title", "정보처리기사", "data", Map.of("certName", "정보처리기사")),
        s.accessToken());
    long boardId = TestFixtures.board(jdbc, s.userId(), "https://smoke.local/" + UUID.randomUUID());
    long postingId =
        jdbc.queryForObject(
            "insert into posting (board_id, title, url, url_hash, type)"
                + " values (?, '스모크 장학', ?, ?, 'scholarship') returning id",
            Long.class,
            boardId,
            "https://smoke.local/p/" + UUID.randomUUID(),
            UUID.randomUUID().toString());
    jdbc.update(
        "insert into posting_parsed (posting_id, parse_status, form_questions) values (?, 'done',"
            + " '[{\"order\":1,\"question\":\"지원 동기를 작성해 주세요\",\"maxChars\":300}]'::jsonb)",
        postingId);

    ResponseEntity<String> created =
        call(HttpMethod.POST, "/applications", Map.of("postingId", postingId), s.accessToken());
    assertOk(created, 201);
    JsonNode createdData = json(created).path("data");
    assertHas(createdData, "id", "status", "items");
    long applicationId = createdData.path("id").asLong();

    JsonNode ready = null;
    for (int i = 0; i < 50; i++) {
      ready =
          json(call(HttpMethod.GET, "/applications/" + applicationId, null, s.accessToken()))
              .path("data");
      if (!"generating".equals(ready.path("status").asText())) {
        break;
      }
      Thread.sleep(100);
    }
    assertHas(
        ready.path("items").get(0), "id", "order", "question", "maxChars", "status", "answer");
    assertThat(ready.path("items").get(0).path("maxChars").asInt()).isPositive(); // FE: 필수 양수
    long itemId = ready.path("items").get(0).path("id").asLong();
    String base = "/applications/" + applicationId;

    assertOk(
        call(HttpMethod.PATCH, base + "/items/" + itemId, Map.of("answer", "수정"), s.accessToken()),
        200);
    assertOk(
        call(HttpMethod.POST, base + "/items/" + itemId + "/regenerate", null, s.accessToken()),
        200);
    assertOk(
        call(HttpMethod.GET, base + "/items/" + itemId + "/versions", null, s.accessToken()), 200);
    assertOk(call(HttpMethod.POST, base + "/save", null, s.accessToken()), 200);
    assertOk(
        call(HttpMethod.PATCH, base + "/result", Map.of("result", "pending"), s.accessToken()),
        200);

    ResponseEntity<String> list = call(HttpMethod.GET, "/applications", null, s.accessToken());
    assertOk(list, 200);
    assertHas(
        json(list).path("data"), "applications", "nextCursor"); // FE: {applications, nextCursor}
    assertHas(
        json(list).path("data").path("applications").get(0),
        "id",
        "postingId",
        "postingTitle",
        "postingType",
        "status",
        "result",
        "scoreSnapshot",
        "items");

    assertOk(call(HttpMethod.DELETE, base, null, s.accessToken()), 200);
  }

  @Test
  @DisplayName("§7·§8 신규 기능·알림 계약")
  void extendedAndNotificationContract() {
    Session s = login();

    ResponseEntity<String> forYou = call(HttpMethod.GET, "/feed/for-you", null, s.accessToken());
    assertOk(forYou, 200);
    assertHas(json(forYou).path("data"), "topPick", "byStrength", "byGap");

    ResponseEntity<String> roadmap =
        call(HttpMethod.GET, "/roadmap/compare?cohort=me_only", null, s.accessToken());
    assertOk(roadmap, 200);
    assertHas(json(roadmap).path("data"), "cohort", "sampleSize", "metrics", "suggestions");

    ResponseEntity<String> export =
        call(HttpMethod.POST, "/export", Map.of("format", "markdown"), s.accessToken());
    assertOk(export, 200);
    assertHas(json(export).path("data"), "format", "content");

    ResponseEntity<String> notifications =
        call(HttpMethod.GET, "/notifications", null, s.accessToken());
    assertOk(notifications, 200);
    assertHas(json(notifications).path("data"), "notifications", "nextCursor");
    assertOk(call(HttpMethod.POST, "/notifications/read-all", null, s.accessToken()), 200);

    ResponseEntity<String> settings =
        call(HttpMethod.GET, "/notifications/settings", null, s.accessToken());
    assertOk(settings, 200);
    assertHas(
        json(settings).path("data"),
        "newPosting",
        "dueSoon",
        "boardError",
        "weeklyReport",
        "quietHours",
        "weekendOff",
        "perBoard");
    assertOk(
        call(
            HttpMethod.PUT,
            "/notifications/settings",
            Map.of("weeklyReport", true),
            s.accessToken()),
        200);
    assertOk(
        call(
            HttpMethod.POST,
            "/notifications/fcm-token",
            Map.of("deviceId", "smoke", "fcmToken", "t"),
            s.accessToken()),
        200);
  }

  @Test
  @DisplayName("실패 계약 — 에러 응답도 {ok:false, error{code,message,field}} 를 지킨다")
  void errorEnvelopeContract() {
    Session s = login();

    ResponseEntity<String> notFound =
        call(HttpMethod.GET, "/postings/999999999", null, s.accessToken());
    assertThat(notFound.getStatusCode().value()).isEqualTo(404);
    JsonNode error = json(notFound);
    assertThat(error.path("ok").asBoolean()).isFalse();
    assertHas(error.path("error"), "code", "message", "field");
    assertThat(error.path("error").path("code").asText()).isEqualTo("POSTING_NOT_FOUND");
  }
}
