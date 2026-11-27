package com.careercompass.experience;

import static org.assertj.core.api.Assertions.assertThat;

import com.careercompass.support.ApiITBase;
import com.careercompass.support.TestFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** M2 — 경험 카드 CRUD (#18): 유형별 필수 필드·30개 상한·커서 페이징·소유권. */
class ExperienceIT extends ApiITBase {

  @Autowired private JdbcTemplate jdbc;

  private ResponseEntity<String> createCard(Session session, Map<String, ?> body) {
    return call(HttpMethod.POST, "/experiences", body, session.accessToken());
  }

  @Test
  @DisplayName("유형별 필수 필드 — award 는 rank 누락 시 field=data.rank, intern 은 startDate 필수")
  void requiredFieldsPerType() {
    Session session = login();

    ResponseEntity<String> awardMissingRank =
        createCard(
            session,
            Map.of(
                "type", "award",
                "title", "교내 해커톤",
                "data", Map.of("contestName", "건국 해커톤")));
    assertThat(awardMissingRank.getStatusCode().value()).isEqualTo(400);
    assertThat(json(awardMissingRank).path("error").path("field").asText()).isEqualTo("data.rank");

    ResponseEntity<String> internMissingStart =
        createCard(
            session,
            Map.of(
                "type", "intern",
                "title", "백엔드 인턴",
                "data", Map.of("company", "카카오", "role", "백엔드")));
    assertThat(internMissingStart.getStatusCode().value()).isEqualTo(400);
    assertThat(json(internMissingStart).path("error").path("field").asText())
        .isEqualTo("startDate");

    ResponseEntity<String> ok =
        createCard(
            session,
            Map.of(
                "type", "award",
                "title", "교내 해커톤",
                "data", Map.of("contestName", "건국 해커톤", "prize", "대상")));
    assertThat(ok.getStatusCode().value()).isEqualTo(201); // v0.2 이름 prize 도 받는다
  }

  @Test
  @DisplayName("FE 가 실제로 보내는 data 키로 다섯 유형 모두 저장된다 (v0.3, FE ExperienceMapper)")
  void feWireKeysAccepted() {
    Session session = login();
    List<Map<String, Object>> bodies =
        List.of(
            Map.of(
                "type", "project",
                "title", "CareerCompass",
                "startDate", "2026-03-01",
                "data", Map.of("role", "백엔드", "techs", List.of("Spring"), "summary", "API")),
            Map.of(
                "type", "award",
                "title", "교내 해커톤 대상",
                "data", Map.of("contestName", "건국 해커톤", "rank", "대상", "year", 2026)),
            Map.of(
                "type", "intern",
                "title", "백엔드 인턴",
                "startDate", "2026-07-01",
                "data", Map.of("company", "카카오", "role", "백엔드")),
            Map.of(
                "type", "activity",
                "title", "멋쟁이사자처럼 14기",
                "data", Map.of("organization", "멋쟁이사자처럼", "role", "운영진")),
            Map.of(
                "type", "cert",
                "title", "정보처리기사",
                "data", Map.of("issuer", "한국산업인력공단", "acquiredYearMonth", "2026-06")));
    for (Map<String, Object> body : bodies) {
      ResponseEntity<String> res = createCard(session, body);
      assertThat(res.getStatusCode().value()).as(body.get("type").toString()).isEqualTo(201);
    }
  }

  @Test
  @DisplayName("커서 페이징 — limit 2 로 두 번에 나눠 최신순으로 받고 마지막 페이지는 nextCursor 가 null")
  void cursorPaging() {
    Session session = login();
    for (int i = 1; i <= 3; i++) {
      assertThat(
              createCard(
                      session,
                      Map.of(
                          "type",
                          "cert",
                          "title",
                          "자격증 " + i,
                          "data",
                          Map.of("certName", "정보처리기사 " + i)))
                  .getStatusCode()
                  .value())
          .isEqualTo(201);
    }

    JsonNode page1 =
        json(call(HttpMethod.GET, "/experiences?limit=2", null, session.accessToken()))
            .path("data");
    assertThat(page1.path("experiences")).hasSize(2);
    assertThat(page1.path("experiences").get(0).path("title").asText()).isEqualTo("자격증 3");
    String nextCursor = page1.path("nextCursor").asText();
    assertThat(nextCursor).isNotBlank();

    JsonNode page2 =
        json(call(
                HttpMethod.GET,
                "/experiences?limit=2&cursor=" + nextCursor,
                null,
                session.accessToken()))
            .path("data");
    assertThat(page2.path("experiences")).hasSize(1);
    assertThat(page2.path("experiences").get(0).path("title").asText()).isEqualTo("자격증 1");
    assertThat(page2.path("nextCursor").isNull()).isTrue();
  }

  @Test
  @DisplayName("type 필터 — 해당 유형만 내려온다")
  void typeFilter() {
    Session session = login();
    createCard(
        session, Map.of("type", "cert", "title", "SQLD", "data", Map.of("certName", "SQLD")));
    createCard(
        session,
        Map.of(
            "type", "activity",
            "title", "멋쟁이사자처럼",
            "data", Map.of("activityName", "멋사", "organization", "멋쟁이사자처럼")));

    JsonNode data =
        json(call(HttpMethod.GET, "/experiences?type=cert", null, session.accessToken()))
            .path("data");
    assertThat(data.path("experiences")).hasSize(1);
    assertThat(data.path("experiences").get(0).path("type").asText()).isEqualTo("cert");
  }

  @Test
  @DisplayName("31번째 카드는 LIMIT_EXCEEDED(422)")
  void thirtyCardCap() {
    Session session = login();
    for (int i = 0; i < 30; i++) {
      TestFixtures.experienceCard(jdbc, session.userId(), "cert", "카드 " + i);
    }

    ResponseEntity<String> res =
        createCard(session, Map.of("type", "cert", "title", "초과", "data", Map.of("certName", "x")));

    assertThat(res.getStatusCode().value()).isEqualTo(422);
    assertThat(json(res).path("error").path("code").asText()).isEqualTo("LIMIT_EXCEEDED");
  }

  @Test
  @DisplayName("남의 카드는 404 — 존재 자체를 감춘다")
  void ownershipHidden() {
    Session owner = login();
    long cardId =
        json(createCard(
                owner, Map.of("type", "cert", "title", "내 카드", "data", Map.of("certName", "x"))))
            .path("data")
            .path("id")
            .asLong();

    Session stranger = login();
    ResponseEntity<String> res =
        call(
            HttpMethod.PATCH,
            "/experiences/" + cardId,
            Map.of("title", "탈취 시도"),
            stranger.accessToken());

    assertThat(res.getStatusCode().value()).isEqualTo(404);
  }

  @Test
  @DisplayName("수정·삭제 — 수정 반영, 삭제 후 목록에서 사라진다")
  void patchAndDelete() {
    Session session = login();
    long cardId =
        json(createCard(
                session,
                Map.of("type", "cert", "title", "원래 제목", "data", Map.of("certName", "SQLD"))))
            .path("data")
            .path("id")
            .asLong();

    ResponseEntity<String> patched =
        call(
            HttpMethod.PATCH,
            "/experiences/" + cardId,
            Map.of("title", "바뀐 제목"),
            session.accessToken());
    assertThat(json(patched).path("data").path("title").asText()).isEqualTo("바뀐 제목");

    assertThat(
            call(HttpMethod.DELETE, "/experiences/" + cardId, null, session.accessToken())
                .getStatusCode()
                .value())
        .isEqualTo(200);
    JsonNode after =
        json(call(HttpMethod.GET, "/experiences", null, session.accessToken())).path("data");
    assertThat(after.path("experiences")).isEmpty();
  }
}
