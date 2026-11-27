package com.careercompass.extended;

import static org.assertj.core.api.Assertions.assertThat;

import com.careercompass.support.ApiITBase;
import com.careercompass.support.TestFixtures;
import com.fasterxml.jackson.databind.JsonNode;
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

/** M7 — For You 추천(#41)·로드맵 코호트 비교(#42)·강점 Export(#43)·동의 저장. */
class ExtendedIT extends ApiITBase {

  @Autowired private JdbcTemplate jdbc;

  private long scoredPosting(
      long userId,
      long boardId,
      String title,
      int score,
      int fieldAxis,
      int prefAxis,
      LocalDate due) {
    long postingId =
        jdbc.queryForObject(
            "insert into posting (board_id, title, url, url_hash, type, due_date)"
                + " values (?, ?, ?, ?, 'recruit', ?) returning id",
            Long.class,
            boardId,
            title,
            "https://x.local/" + UUID.randomUUID(),
            UUID.randomUUID().toString(),
            due);
    String breakdown =
        String.format(
            "[{\"axis\":\"field_similarity\",\"score\":%d,\"weight\":40},"
                + "{\"axis\":\"qualification\",\"score\":100,\"weight\":30},"
                + "{\"axis\":\"preference\",\"score\":%d,\"weight\":20},"
                + "{\"axis\":\"competition\",\"score\":50,\"weight\":10}]",
            fieldAxis, prefAxis);
    jdbc.update(
        "insert into suitability_score (user_id, posting_id, score, label, breakdown)"
            + " values (?, ?, ?, 'suitable', ?::jsonb)",
        userId,
        postingId,
        score,
        breakdown);
    return postingId;
  }

  @Test
  @DisplayName("For You — 톱 픽·강점 기반·취약점 보완이 근거 문구와 함께 나뉜다 (#41)")
  void forYouCategories() {
    Session session = login();
    long boardId =
        TestFixtures.board(jdbc, session.userId(), "https://x.local/" + UUID.randomUUID());
    long top =
        scoredPosting(session.userId(), boardId, "톱 픽 공고", 92, 90, 80, LocalDate.now().plusDays(3));
    long strength =
        scoredPosting(session.userId(), boardId, "강점 공고", 75, 70, 60, LocalDate.now().plusDays(20));
    long gap = scoredPosting(session.userId(), boardId, "보완 공고", 55, 70, 20, null);

    JsonNode data =
        json(call(HttpMethod.GET, "/feed/for-you", null, session.accessToken())).path("data");

    assertThat(data.path("topPick").path("postingId").asLong()).isEqualTo(top);
    assertThat(data.path("topPick").path("reason").toString()).contains("적합도").contains("마감");
    assertThat(data.path("byStrength").toString()).contains(String.valueOf(strength));
    assertThat(data.path("byGap").toString()).contains(String.valueOf(gap));
    assertThat(data.path("byStrength").get(0).path("reason").asText()).isNotBlank();

    // 점수 없는 신규 사용자는 빈 추천 — 프로필이 다르면 추천도 다르다
    Session fresh = login();
    JsonNode empty =
        json(call(HttpMethod.GET, "/feed/for-you", null, fresh.accessToken())).path("data");
    assertThat(empty.path("topPick").isNull()).isTrue();
    assertThat(empty.path("byStrength")).isEmpty();
  }

  @Test
  @DisplayName("로드맵 peer — 표본 5명 미만이면 metrics 가 비고, 채워지면 평균이 나온다 (#42)")
  void roadmapPeerCohort() {
    Session session = login();
    String dept = "로드맵학과" + (session.userId() % 100000);
    call(
        HttpMethod.PATCH,
        "/users/me",
        Map.of("school", "건국대학교", "department", dept, "gradYear", 2027),
        session.accessToken());
    jdbc.update(
        "update user_profile set department = ?, grad_year = 2027 where user_id = ?",
        dept,
        session.userId());

    JsonNode insufficient =
        json(call(HttpMethod.GET, "/roadmap/compare?cohort=peer", null, session.accessToken()))
            .path("data");
    assertThat(insufficient.path("sampleSize").asInt()).isLessThan(5);
    assertThat(insufficient.path("metrics")).isEmpty();

    for (int i = 0; i < 5; i++) {
      long peerId = TestFixtures.user(jdbc);
      jdbc.update(
          "update user_profile set department = ?, grad_year = 2027 where user_id = ?",
          dept,
          peerId);
      TestFixtures.experienceCard(jdbc, peerId, "project", "피어 프로젝트 A");
      TestFixtures.experienceCard(jdbc, peerId, "project", "피어 프로젝트 B");
    }

    JsonNode data =
        json(call(HttpMethod.GET, "/roadmap/compare?cohort=peer", null, session.accessToken()))
            .path("data");
    assertThat(data.path("sampleSize").asInt()).isEqualTo(5);
    assertThat(data.path("metrics").size()).isEqualTo(4);
    JsonNode projectMetric = null;
    for (JsonNode metric : data.path("metrics")) {
      if (metric.path("name").asText().equals("프로젝트 수")) {
        projectMetric = metric;
      }
    }
    assertThat(projectMetric).isNotNull();
    assertThat(projectMetric.path("peerAvg").asDouble()).isEqualTo(2.0);
    assertThat(projectMetric.path("me").asDouble()).isZero();
    assertThat(data.path("suggestions").get(0).path("action").asText()).contains("프로젝트");
  }

  @Test
  @DisplayName("로드맵 senior — pass 결과 + 동의 사용자만 코호트에 든다 (#42, #48)")
  void roadmapSeniorCohort() {
    Session session = login();
    long boardId =
        TestFixtures.board(jdbc, session.userId(), "https://x.local/" + UUID.randomUUID());
    long postingId = TestFixtures.posting(jdbc, boardId, "선배 공고");

    for (int i = 0; i < 5; i++) {
      long seniorId = TestFixtures.user(jdbc);
      TestFixtures.experienceCard(jdbc, seniorId, "intern", "선배 인턴 " + i);
      jdbc.update(
          "insert into application (user_id, posting_id, status, tone, result)"
              + " values (?, ?, 'saved', 'formal', 'pass')",
          seniorId,
          postingId);
      jdbc.update(
          "insert into user_consent (user_id, model_improvement) values (?, true)", seniorId);
    }

    JsonNode data =
        json(call(HttpMethod.GET, "/roadmap/compare?cohort=senior", null, session.accessToken()))
            .path("data");
    assertThat(data.path("sampleSize").asInt()).isGreaterThanOrEqualTo(5);
    assertThat(data.path("metrics")).isNotEmpty();
  }

  @Test
  @DisplayName("me_only 는 표본 1로 내 지표만, 알 수 없는 cohort 는 400")
  void meOnlyAndValidation() {
    Session session = login();
    call(
        HttpMethod.POST,
        "/experiences",
        Map.of("type", "cert", "title", "SQLD", "data", Map.of("certName", "SQLD")),
        session.accessToken());

    JsonNode data =
        json(call(HttpMethod.GET, "/roadmap/compare?cohort=me_only", null, session.accessToken()))
            .path("data");
    assertThat(data.path("sampleSize").asInt()).isEqualTo(1);
    assertThat(data.path("metrics").toString()).contains("자격증");
    // FE 는 peerAvg 를 숫자로만 받는다 — me_only 는 비교 대상이 없어 me 와 같은 값
    for (JsonNode metric : data.path("metrics")) {
      assertThat(metric.path("peerAvg").isNumber()).isTrue();
      assertThat(metric.path("peerAvg").asDouble()).isEqualTo(metric.path("me").asDouble());
    }

    ResponseEntity<String> bad =
        call(HttpMethod.GET, "/roadmap/compare?cohort=unknown", null, session.accessToken());
    assertThat(bad.getStatusCode().value()).isEqualTo(400);
  }

  @Test
  @DisplayName("Export 4포맷 — 각각 붙여넣어 쓸 수 있는 형태로 나온다 (#43)")
  void exportFormats() {
    Session session = login();
    call(
        HttpMethod.PATCH,
        "/users/me",
        Map.of("name", "조영탁", "school", "건국대학교", "department", "컴퓨터공학부"),
        session.accessToken());
    call(
        HttpMethod.POST,
        "/experiences",
        Map.of(
            "type", "project",
            "title", "CareerCompass",
            "startDate", "2026-01-01",
            "data", Map.of("summary", "Spring 백엔드 개발")),
        session.accessToken());

    JsonNode markdown =
        json(call(
                HttpMethod.POST,
                "/export",
                Map.of("format", "markdown", "sections", List.of("basic", "projects", "summary")),
                session.accessToken()))
            .path("data");
    assertThat(markdown.path("content").asText())
        .contains("# 조영탁")
        .contains("## 기본 정보")
        .contains("CareerCompass");

    JsonNode html =
        json(call(HttpMethod.POST, "/export", Map.of("format", "html"), session.accessToken()))
            .path("data");
    assertThat(html.path("content").asText()).contains("<h2>기본 정보</h2>");

    JsonNode plain =
        json(call(HttpMethod.POST, "/export", Map.of("format", "plain"), session.accessToken()))
            .path("data");
    assertThat(plain.path("content").asText()).contains("[기본 정보]");

    JsonNode notion =
        json(call(HttpMethod.POST, "/export", Map.of("format", "notion"), session.accessToken()))
            .path("data");
    assertThat(notion.path("content").asText()).contains("## 기본 정보");

    ResponseEntity<String> bad =
        call(HttpMethod.POST, "/export", Map.of("format", "pdf"), session.accessToken());
    assertThat(bad.getStatusCode().value()).isEqualTo(400);
  }

  @Test
  @DisplayName("모델 개선 동의 — 설정·철회가 반영된다 (#48 연계)")
  void consentToggle() {
    Session session = login();

    JsonNode on =
        json(call(
                HttpMethod.PUT,
                "/users/me/consent",
                Map.of("modelImprovement", true),
                session.accessToken()))
            .path("data");
    assertThat(on.path("modelImprovement").asBoolean()).isTrue();

    JsonNode off =
        json(call(
                HttpMethod.PUT,
                "/users/me/consent",
                Map.of("modelImprovement", false),
                session.accessToken()))
            .path("data");
    assertThat(off.path("modelImprovement").asBoolean()).isFalse();
  }
}
