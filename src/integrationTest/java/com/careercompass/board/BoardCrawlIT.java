package com.careercompass.board;

import static org.assertj.core.api.Assertions.assertThat;

import com.careercompass.board.crawl.CrawlEvents;
import com.careercompass.board.crawl.CrawlService;
import com.careercompass.support.ApiITBase;
import com.careercompass.support.FixtureWebServer;
import com.careercompass.support.TestFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/** M3 — 구조 감지(#21)·실패 분류(#22)·CRUD(#23)·수집/중복 방지(#24)·재시도/비활성화(#25)·robots(#26). */
@RecordApplicationEvents
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BoardCrawlIT extends ApiITBase {

  private static final FixtureWebServer web = new FixtureWebServer();

  @Autowired private JdbcTemplate jdbc;
  @Autowired private CrawlService crawlService;
  @Autowired private ApplicationEvents events;

  @BeforeAll
  static void startServer() throws Exception {
    web.start();
  }

  @AfterAll
  static void stopServer() {
    web.stop();
  }

  private ResponseEntity<String> detect(Session session, String url) {
    return call(HttpMethod.POST, "/boards/detect", Map.of("url", url), session.accessToken());
  }

  private ResponseEntity<String> register(Session session, String url, String name) {
    return call(
        HttpMethod.POST,
        "/boards",
        Map.of("url", url, "name", name, "type", "scholarship", "cycleHours", 24),
        session.accessToken());
  }

  @Test
  @DisplayName("구조 감지 성공 — 셀렉터와 미리보기 5건이 내려온다 (#21)")
  void detectSuccess() {
    Session session = login();

    ResponseEntity<String> res = detect(session, web.baseUrl() + "/board");

    JsonNode data = json(res).path("data");
    assertThat(data.path("detectStatus").asText()).isEqualTo("success");
    assertThat(data.path("selectors").path("title").asText()).isNotBlank();
    assertThat(data.path("preview")).hasSize(5);
    assertThat(data.path("preview").get(0).path("title").asText()).contains("장학금");
    assertThat(data.path("preview").get(0).path("url").asText()).startsWith("http://127.0.0.1");
    assertThat(data.path("preview").get(0).path("date").asText()).startsWith("2026-09");
  }

  @Test
  @DisplayName("감지 실패 분류 — login_required·spa·blocked·failed (#22)")
  void detectFailureKinds() {
    Session session = login();

    assertThat(
            json(detect(session, web.baseUrl() + "/login"))
                .path("data")
                .path("detectStatus")
                .asText())
        .isEqualTo("login_required");
    assertThat(
            json(detect(session, web.baseUrl() + "/spa"))
                .path("data")
                .path("detectStatus")
                .asText())
        .isEqualTo("spa");
    assertThat(
            json(detect(session, web.baseUrl() + "/blocked"))
                .path("data")
                .path("detectStatus")
                .asText())
        .isEqualTo("blocked");
    assertThat(
            json(detect(session, "http://127.0.0.1:1/dead"))
                .path("data")
                .path("detectStatus")
                .asText())
        .isEqualTo("failed");
  }

  @Test
  @DisplayName("robots.txt Disallow 경로는 감지가 blocked, 등록은 BOARD_BLOCKED(422) (#26)")
  void robotsRespected() {
    Session session = login();

    assertThat(
            json(detect(session, web.baseUrl() + "/private/board"))
                .path("data")
                .path("detectStatus")
                .asText())
        .isEqualTo("blocked");

    ResponseEntity<String> res = register(session, web.baseUrl() + "/private/board", "차단 게시판");
    assertThat(res.getStatusCode().value()).isEqualTo(422);
    assertThat(json(res).path("error").path("code").asText()).isEqualTo("BOARD_BLOCKED");
  }

  @Test
  @DisplayName("등록 — 201 + 즉시 1회 수집으로 공고가 쌓이고, 같은 URL 재등록은 409 (#23 #24)")
  void registerCollectsImmediately() {
    Session session = login();

    ResponseEntity<String> created = register(session, web.baseUrl() + "/board", "학과 공지");
    assertThat(created.getStatusCode().value()).isEqualTo(201);
    JsonNode board = json(created).path("data");
    long boardId = board.path("id").asLong();
    assertThat(board.path("status").asText()).isEqualTo("active");
    assertThat(board.path("lastCollectedAt").asText()).isNotBlank();

    Integer postings =
        jdbc.queryForObject(
            "select count(*) from posting where board_id = ?", Integer.class, boardId);
    assertThat(postings).isEqualTo(8);
    // 저장 항목 검증: 본문·마감일·파싱 대기 상태 (#24, #27)
    Map<String, Object> one =
        jdbc.queryForMap(
            "select p.raw_content, p.due_date, pp.parse_status from posting p"
                + " join posting_parsed pp on pp.posting_id = p.id where p.board_id = ? limit 1",
            boardId);
    assertThat((String) one.get("raw_content")).contains("장학금 공고 본문");
    assertThat(one.get("due_date").toString()).isEqualTo("2026-09-30");
    assertThat(one.get("parse_status")).isEqualTo("pending");

    // 트레일링 슬래시가 붙어도 같은 게시판 — 정규화 후 409
    ResponseEntity<String> dup = register(session, web.baseUrl() + "/board/", "중복");
    assertThat(dup.getStatusCode().value()).isEqualTo(409);
    assertThat(json(dup).path("error").path("code").asText()).isEqualTo("DUPLICATE_BOARD");
  }

  @Test
  @DisplayName("재수집은 신규만 쌓는다 — 중복 URL 은 재수집하지 않는다 (#24)")
  void recrawlOnlyAddsNew() {
    Session session = login();
    long boardId =
        json(register(session, web.baseUrl() + "/board?u=" + session.userId(), "중복 방지"))
            .path("data")
            .path("id")
            .asLong();
    Integer initial =
        jdbc.queryForObject(
            "select count(*) from posting where board_id = ?", Integer.class, boardId);

    web.addPost();
    try {
      call(HttpMethod.POST, "/boards/" + boardId + "/retry", null, session.accessToken());
      Integer afterNew =
          jdbc.queryForObject(
              "select count(*) from posting where board_id = ?", Integer.class, boardId);
      assertThat(afterNew).isEqualTo(initial + 1);

      call(HttpMethod.POST, "/boards/" + boardId + "/retry", null, session.accessToken());
      Integer afterRepeat =
          jdbc.queryForObject(
              "select count(*) from posting where board_id = ?", Integer.class, boardId);
      assertThat(afterRepeat).isEqualTo(afterNew);
    } finally {
      web.setFailMode(false);
    }
  }

  @Test
  @DisplayName("3연속 수집 실패 → status=error + 비활성화 이벤트, 수동 재시도로 복구 (#25)")
  void threeFailuresDisableBoard() {
    Session session = login();
    long boardId =
        json(register(session, web.baseUrl() + "/board?fail=" + session.userId(), "실패 대상"))
            .path("data")
            .path("id")
            .asLong();

    web.setFailMode(true);
    try {
      for (int i = 0; i < 3; i++) {
        crawlService.crawlBoard(boardId);
      }
    } finally {
      web.setFailMode(false);
    }

    JsonNode listed =
        json(call(HttpMethod.GET, "/boards", null, session.accessToken()))
            .path("data")
            .path("boards");
    JsonNode target = null;
    for (JsonNode b : listed) {
      if (b.path("id").asLong() == boardId) {
        target = b;
      }
    }
    assertThat(target).isNotNull();
    // 바깥 어휘는 FE 의 active|paused|failed — 수집 오류는 failed (v0.3)
    assertThat(target.path("status").asText()).isEqualTo("failed");
    assertThat(target.path("failCount").asInt()).isEqualTo(3);
    assertThat(events.stream(CrawlEvents.BoardDisabled.class).count()).isGreaterThanOrEqualTo(1);

    ResponseEntity<String> retried =
        call(HttpMethod.POST, "/boards/" + boardId + "/retry", null, session.accessToken());
    JsonNode data = json(retried).path("data");
    assertThat(data.path("status").asText()).isEqualTo("active");
    assertThat(data.path("failCount").asInt()).isZero();
  }

  @Test
  @DisplayName("21번째 게시판은 LIMIT_EXCEEDED(422) (#23)")
  void boardCap() {
    Session session = login();
    for (int i = 0; i < 20; i++) {
      TestFixtures.board(jdbc, session.userId(), "https://ex" + i + ".test/" + session.userId());
    }

    ResponseEntity<String> res = register(session, web.baseUrl() + "/board?cap=1", "초과");

    assertThat(res.getStatusCode().value()).isEqualTo(422);
    assertThat(json(res).path("error").path("code").asText()).isEqualTo("LIMIT_EXCEEDED");
  }

  @Test
  @DisplayName("수정·삭제 — isActive 토글이 반영되고, 삭제 시 공고도 함께 사라진다 (#23)")
  void patchAndDelete() {
    Session session = login();
    long boardId =
        json(register(session, web.baseUrl() + "/board?pd=" + session.userId(), "수정 대상"))
            .path("data")
            .path("id")
            .asLong();

    JsonNode patched =
        json(call(
                HttpMethod.PATCH,
                "/boards/" + boardId,
                Map.of("isActive", false, "name", "바뀐 이름"),
                session.accessToken()))
            .path("data");
    assertThat(patched.path("isActive").asBoolean()).isFalse();
    assertThat(patched.path("name").asText()).isEqualTo("바뀐 이름");

    call(HttpMethod.DELETE, "/boards/" + boardId, null, session.accessToken());
    Integer postings =
        jdbc.queryForObject(
            "select count(*) from posting where board_id = ?", Integer.class, boardId);
    assertThat(postings).isZero();
  }
}
