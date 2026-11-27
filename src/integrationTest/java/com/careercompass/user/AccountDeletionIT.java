package com.careercompass.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.careercompass.support.ApiITBase;
import com.careercompass.support.TestFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;

/** #61 — 계정 삭제: 모든 테이블에서 사라지고, 원본 파일도 지워지고, 남은 토큰은 401. */
class AccountDeletionIT extends ApiITBase {

  @Autowired private JdbcTemplate jdbc;

  /** user_id 컬럼을 가진 모든 테이블 — 새 테이블이 생겨도 이 테스트가 빠진 삭제를 잡는다. */
  private List<String> tablesWithUserId() {
    return jdbc.queryForList(
        "select table_name from information_schema.columns"
            + " where table_schema = 'public' and column_name = 'user_id'",
        String.class);
  }

  private void seedEverything(Session session) {
    long userId = session.userId();
    String token = session.accessToken();
    call(
        HttpMethod.POST,
        "/experiences",
        Map.of(
            "type", "project",
            "title", "삭제될 경험",
            "startDate", "2026-01-01",
            "data", Map.of("summary", "Spring 백엔드")),
        token);
    upload(
        "/past-applications/upload",
        "old.txt",
        "지원 동기는 성장입니다. 입사 후 포부는 기여입니다.".getBytes(StandardCharsets.UTF_8),
        "옛 자소서",
        token);
    call(HttpMethod.PUT, "/users/me/tags", Map.of("tags", List.of("백엔드")), token);
    call(HttpMethod.PUT, "/users/me/consent", Map.of("modelImprovement", true), token);
    call(HttpMethod.GET, "/notifications/settings", null, token);

    long boardId = TestFixtures.board(jdbc, userId, "https://delete.local/" + UUID.randomUUID());
    long postingId = TestFixtures.posting(jdbc, boardId, "삭제될 공고");
    jdbc.update(
        "insert into posting_parsed (posting_id, parse_status, keywords, form_questions)"
            + " values (?, 'done', '[\"백엔드\",\"Spring\",\"AWS\"]'::jsonb,"
            + " '[{\"order\":1,\"question\":\"지원 동기\",\"maxChars\":300}]'::jsonb)",
        postingId);
    call(HttpMethod.POST, "/postings/" + postingId + "/bookmark", null, token);
    call(HttpMethod.POST, "/applications", Map.of("postingId", postingId), token);
    jdbc.update(
        "insert into notification (user_id, type, title, body, deeplink, dedup_key)"
            + " values (?, 'new_posting', '새 공고', '1건', 'careercompass://postings', ?)",
        userId,
        "test-" + UUID.randomUUID());
  }

  @Test
  @DisplayName("계정 삭제 — user_id 를 가진 모든 테이블과 users 에서 사라지고 원본 파일도 지워진다")
  void deletesEverything() throws Exception {
    Session session = login();
    seedEverything(session);
    long userId = session.userId();
    List<String> files =
        jdbc.queryForList(
            "select file_path from past_application where user_id = ?", String.class, userId);
    assertThat(files).isNotEmpty();
    Path stored = Path.of("build/it-storage").resolve(files.get(0).substring("local://".length()));
    assertThat(Files.exists(stored)).isTrue();

    var res = call(HttpMethod.DELETE, "/users/me", null, session.accessToken());
    assertThat(res.getStatusCode().value()).isEqualTo(200);

    for (String table : tablesWithUserId()) {
      Integer rows =
          jdbc.queryForObject(
              "select count(*) from " + table + " where user_id = ?", Integer.class, userId);
      assertThat(rows).as("테이블 %s 에 지운 계정의 행이 남았다", table).isZero();
    }
    assertThat(
            jdbc.queryForObject("select count(*) from users where id = ?", Integer.class, userId))
        .isZero();
    assertThat(Files.exists(stored)).isFalse();
  }

  @Test
  @DisplayName("지운 계정의 access 토큰은 401, refresh 토큰도 401 — 다른 사용자는 영향 없음")
  void tokensDieWithAccount() {
    Session doomed = login();
    Session bystander = login();
    seedEverything(bystander);

    call(HttpMethod.DELETE, "/users/me", null, doomed.accessToken());

    var me = call(HttpMethod.GET, "/users/me", null, doomed.accessToken());
    assertThat(me.getStatusCode().value()).isEqualTo(401);
    var refreshed =
        call(HttpMethod.POST, "/auth/refresh", Map.of("refreshToken", doomed.refreshToken()), null);
    assertThat(refreshed.getStatusCode().value()).isEqualTo(401);

    JsonNode other =
        json(call(HttpMethod.GET, "/experiences", null, bystander.accessToken())).path("data");
    assertThat(other.toString()).contains("삭제될 경험");
  }
}
