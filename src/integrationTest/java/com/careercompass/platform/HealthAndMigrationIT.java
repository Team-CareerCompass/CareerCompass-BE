package com.careercompass.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.careercompass.support.IntegrationTestBase;
import com.careercompass.support.TestFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** M0 완료 조건 검증 — 앱 기동(#1)·마이그레이션(#2)·인증 계약(#3)·픽스처(#9). */
class HealthAndMigrationIT extends IntegrationTestBase {

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;

  @Test
  @DisplayName("앱이 뜨고 /actuator/health 가 UP 을 준다")
  void healthIsUp() {
    ResponseEntity<String> res = rest.getForEntity("/actuator/health", String.class);

    assertThat(res.getStatusCode().value()).isEqualTo(200);
    assertThat(res.getBody()).contains("UP");
  }

  @org.springframework.boot.test.web.server.LocalServerPort private int port;

  private java.net.http.HttpResponse<String> raw(String path) throws Exception {
    // Accept 헤더 없이 — curl·로드밸런서 헬스체크와 같은 요청
    return java.net.http.HttpClient.newHttpClient()
        .send(
            java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create("http://127.0.0.1:" + port + path))
                .GET()
                .build(),
            java.net.http.HttpResponse.BodyHandlers.ofString());
  }

  @Test
  @DisplayName("모든 경로는 /api/v1 아래 — Accept 없는 헬스체크도 200, 접두사 없는 경로는 없다")
  void apiPrefixAndPlainHealthCheck() throws Exception {
    var health = raw("/api/v1/actuator/health");
    assertThat(health.statusCode()).isEqualTo(200);
    assertThat(health.body()).contains("UP");

    assertThat(raw("/api/v1/users/me").statusCode()).isEqualTo(401);
    assertThat(raw("/users/me").statusCode()).isEqualTo(404);
  }

  @Test
  @DisplayName("빈 PostgreSQL 에 Flyway 마이그레이션이 적용되어 전체 스키마가 선다")
  void migrationAppliesFromScratch() {
    Integer applied =
        jdbc.queryForObject(
            "select count(*) from flyway_schema_history where success", Integer.class);
    assertThat(applied).isGreaterThanOrEqualTo(1);

    Integer tables =
        jdbc.queryForObject(
            "select count(*) from information_schema.tables"
                + " where table_schema = 'public' and table_type = 'BASE TABLE'",
            Integer.class);
    // ERD 16 + user_device/refresh_token + school/job_category + application_item_version
    // + user_consent + flyway_schema_history
    assertThat(tables).isEqualTo(23);
  }

  @Test
  @DisplayName("인증 없는 보호 경로 요청은 401 + AUTH_REQUIRED 계약 본문을 받는다")
  void unauthenticatedRequestGetsContractBody() {
    ResponseEntity<String> res = rest.getForEntity("/postings", String.class);

    assertThat(res.getStatusCode().value()).isEqualTo(401);
    assertThat(res.getBody()).contains("\"AUTH_REQUIRED\"");
    assertThat(res.getBody()).contains("\"ok\":false");
  }

  @Test
  @DisplayName("픽스처 빌더로 사용자·게시판·공고·경험 카드를 넣을 수 있다")
  void fixturesInsert() {
    long userId = TestFixtures.user(jdbc);
    long boardId = TestFixtures.board(jdbc, userId, "https://board.test/" + userId);
    long postingId = TestFixtures.posting(jdbc, boardId, "2026 테스트 채용");
    long cardId = TestFixtures.experienceCard(jdbc, userId, "project", "CareerCompass");

    assertThat(userId).isPositive();
    assertThat(boardId).isPositive();
    assertThat(postingId).isPositive();
    assertThat(cardId).isPositive();
  }
}
