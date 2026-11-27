package com.careercompass.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.careercompass.analysis.application.AnalysisService;
import com.careercompass.support.ApiITBase;
import com.careercompass.support.TestFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;

/** M5 — 파싱 파이프라인(#31)·4축 산출(#32)·코멘트(#33)·산출 불가 사유(#34) 끝까지. */
class AnalysisFlowIT extends ApiITBase {

  @Autowired private JdbcTemplate jdbc;
  @Autowired private AnalysisService analysisService;

  private static final String RAW_CONTENT =
      """
      지원 자격: 4년제 대학 재학생, 학점 3.0 이상, 컴퓨터공학과 전공자
      백엔드 서버를 함께 만들 Spring 개발자를 찾습니다. 백엔드 실무를 경험할 수 있습니다.
      우대사항:
      - Spring 백엔드 개발 경험 우대
      모집 인원: 5명
      접수기간: 2026.11.01 ~ 2026.12.31 마감
      1. 지원 동기를 작성해 주세요 (500자)
      """;

  private long seedPosting(long userId) {
    long boardId = TestFixtures.board(jdbc, userId, "https://a.local/" + UUID.randomUUID());
    long postingId =
        jdbc.queryForObject(
            "insert into posting (board_id, title, url, url_hash, raw_content, type)"
                + " values (?, '2026 백엔드 개발자 채용', ?, ?, ?, 'recruit') returning id",
            Long.class,
            boardId,
            "https://a.local/p/" + UUID.randomUUID(),
            UUID.randomUUID().toString(),
            RAW_CONTENT);
    jdbc.update("insert into posting_parsed (posting_id) values (?)", postingId);
    return postingId;
  }

  private void completeProfile(Session session) {
    call(
        HttpMethod.PATCH,
        "/users/me",
        Map.of("school", "건국대학교", "department", "컴퓨터공학부", "gpa", 3.5),
        session.accessToken());
    call(
        HttpMethod.PUT,
        "/users/me/job-interests",
        Map.of("interests", List.of(Map.of("code", "backend", "priority", 1))),
        session.accessToken());
    call(
        HttpMethod.PUT,
        "/users/me/tags",
        Map.of("tags", List.of("Spring", "AI")),
        session.accessToken());
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

  @Test
  @DisplayName("파싱 → 산출 → 상세 응답까지 — 4축 breakdown·코멘트·마감일 갱신 (#31 #32 #33)")
  void fullPipeline() {
    Session session = login();
    completeProfile(session);
    long postingId = seedPosting(session.userId());

    assertThat(analysisService.parsePosting(postingId)).isTrue();
    analysisService.computeScore(session.userId(), postingId);

    JsonNode detail =
        json(call(HttpMethod.GET, "/postings/" + postingId, null, session.accessToken()))
            .path("data");
    assertThat(detail.path("parseStatus").asText()).isEqualTo("done");
    assertThat(detail.path("dueDate").asText()).isEqualTo("2026-12-31"); // 파싱이 마감일을 채움
    assertThat(detail.path("parsed").path("keywords").size()).isGreaterThanOrEqualTo(3);
    assertThat(detail.path("parsed").path("formQuestions")).hasSize(1);

    JsonNode suitability = detail.path("suitability");
    assertThat(suitability.path("score").asInt()).isBetween(1, 100);
    assertThat(suitability.path("breakdown")).hasSize(4);
    int weightSum = 0;
    int weighted = 0;
    for (JsonNode axis : suitability.path("breakdown")) {
      weightSum += axis.path("weight").asInt();
      weighted += axis.path("score").asInt() * axis.path("weight").asInt();
    }
    assertThat(weightSum).isEqualTo(100);
    assertThat(suitability.path("score").asInt()).isEqualTo(Math.round(weighted / 100f));
    assertThat(suitability.path("strengthComment").asText()).isNotBlank();
    assertThat(suitability.path("weaknessComment").asText()).isNotBlank();
    assertThat(detail.path("scoreUnavailableReason").isNull()).isTrue();

    // 목록에서도 점수·score_desc 정렬이 동작
    JsonNode listed =
        json(call(
                HttpMethod.GET,
                "/postings?sort=score_desc&includeExpired=true",
                null,
                session.accessToken()))
            .path("data");
    assertThat(listed.path("postings").get(0).path("score").asInt())
        .isEqualTo(suitability.path("score").asInt());
  }

  @Test
  @DisplayName("산출 불가 사유 — 프로필 미입력은 profile_incomplete, 파싱 실패는 parsing_failed, 대기는 analyzing (#34)")
  void unavailableReasons() {
    // 프로필 미입력 사용자
    Session empty = login();
    long pendingPosting = seedPosting(empty.userId());
    JsonNode card =
        json(call(HttpMethod.GET, "/postings?includeExpired=true", null, empty.accessToken()))
            .path("data")
            .path("postings")
            .get(0);
    assertThat(card.path("scoreUnavailableReason").asText()).isEqualTo("profile_incomplete");

    // 프로필 완성 사용자 + 파싱 실패 공고
    Session complete = login();
    completeProfile(complete);
    long failedPosting = seedPosting(complete.userId());
    jdbc.update(
        "update posting_parsed set parse_status = 'failed' where posting_id = ?", failedPosting);
    JsonNode failedCard =
        json(call(HttpMethod.GET, "/postings?includeExpired=true", null, complete.accessToken()))
            .path("data")
            .path("postings")
            .get(0);
    assertThat(failedCard.path("scoreUnavailableReason").asText()).isEqualTo("parsing_failed");

    // 같은 사용자, 파싱 대기 공고 → analyzing
    long analyzing = seedPosting(complete.userId());
    JsonNode analyzingDetail =
        json(call(HttpMethod.GET, "/postings/" + analyzing, null, complete.accessToken()))
            .path("data");
    assertThat(analyzingDetail.path("scoreUnavailableReason").asText()).isEqualTo("analyzing");
  }

  @Test
  @DisplayName("키워드가 부족한 공고는 failed 로 확정된다 (#31)")
  void parseFailureMarksFailed() {
    Session session = login();
    long boardId =
        TestFixtures.board(jdbc, session.userId(), "https://a.local/" + UUID.randomUUID());
    long postingId =
        jdbc.queryForObject(
            "insert into posting (board_id, title, url, url_hash, raw_content, type)"
                + " values (?, '공지', ?, ?, '안내.', 'other') returning id",
            Long.class,
            boardId,
            "https://a.local/p/" + UUID.randomUUID(),
            UUID.randomUUID().toString());
    jdbc.update("insert into posting_parsed (posting_id) values (?)", postingId);

    assertThat(analysisService.parsePosting(postingId)).isFalse();

    String status =
        jdbc.queryForObject(
            "select parse_status from posting_parsed where posting_id = ?",
            String.class,
            postingId);
    assertThat(status).isEqualTo("failed");
  }

  @Test
  @DisplayName("프로필 변경 시 점수 무효화 — 스윕이 다시 채우기 전까지 점수가 비워진다 (#32 재산출 정책)")
  void invalidationClearsScores() {
    Session session = login();
    completeProfile(session);
    long postingId = seedPosting(session.userId());
    analysisService.parsePosting(postingId);
    analysisService.computeScore(session.userId(), postingId);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from suitability_score where user_id = ?",
                Integer.class,
                session.userId()))
        .isEqualTo(1);

    analysisService.invalidateScores(session.userId());

    assertThat(
            jdbc.queryForObject(
                "select count(*) from suitability_score where user_id = ?",
                Integer.class,
                session.userId()))
        .isZero();

    // 재계산도 같은 경로로 가능
    analysisService.computeScore(session.userId(), postingId);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from suitability_score where user_id = ?",
                Integer.class,
                session.userId()))
        .isEqualTo(1);
  }
}
