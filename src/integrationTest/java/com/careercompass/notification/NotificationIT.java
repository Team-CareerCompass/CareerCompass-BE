package com.careercompass.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.careercompass.board.crawl.CrawlEvents;
import com.careercompass.notification.application.NotificationService;
import com.careercompass.notification.application.NotificationTriggers;
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
import org.springframework.jdbc.core.JdbcTemplate;

/** M8 — FCM 파이프라인(#44)·알림함/딥링크(#45)·설정/방해 금지(#46)·트리거 3종(#47). */
class NotificationIT extends ApiITBase {

  @Autowired private JdbcTemplate jdbc;
  @Autowired private NotificationTriggers triggers;
  @Autowired private NotificationService notificationService;

  private JsonNode notifications(Session session) {
    return json(call(HttpMethod.GET, "/notifications", null, session.accessToken())).path("data");
  }

  private void bookmark(long userId, long postingId) {
    jdbc.update(
        "insert into posting_user_state (user_id, posting_id, is_bookmarked, bookmarked_at)"
            + " values (?, ?, true, now())",
        userId,
        postingId);
  }

  private long postingWithDue(long boardId, String title, LocalDate due) {
    return jdbc.queryForObject(
        "insert into posting (board_id, title, url, url_hash, type, due_date)"
            + " values (?, ?, ?, ?, 'recruit', ?) returning id",
        Long.class,
        boardId,
        title,
        "https://n.local/" + UUID.randomUUID(),
        UUID.randomUUID().toString(),
        due);
  }

  @Test
  @DisplayName("신규 공고 트리거 — 여러 건은 묶여 1건으로 오고, 같은 사건은 재발송되지 않는다 (#47)")
  void newPostingBundledAndDeduped() {
    Session session = login();
    long boardId =
        TestFixtures.board(jdbc, session.userId(), "https://n.local/" + UUID.randomUUID());
    long p1 = TestFixtures.posting(jdbc, boardId, "공고 하나");
    long p2 = TestFixtures.posting(jdbc, boardId, "공고 둘");

    CrawlEvents.NewPostingsCollected event =
        new CrawlEvents.NewPostingsCollected(boardId, session.userId(), List.of(p1, p2));
    triggers.onNewPostings(event);
    triggers.onNewPostings(event); // 같은 사건 재발송 시도

    JsonNode data = notifications(session);
    assertThat(data.path("notifications")).hasSize(1);
    JsonNode notification = data.path("notifications").get(0);
    assertThat(notification.path("type").asText()).isEqualTo("new_posting");
    assertThat(notification.path("title").asText()).contains("2건");
    assertThat(notification.path("deeplink").asText()).contains("boardId=" + boardId);
    assertThat(notification.path("isRead").asBoolean()).isFalse();
  }

  @Test
  @DisplayName("수집 오류 트리거 — 게시판 비활성화 이벤트가 알림으로 남고 딥링크가 게시판을 가리킨다 (#25 #47)")
  void boardErrorTrigger() {
    Session session = login();
    long boardId =
        TestFixtures.board(jdbc, session.userId(), "https://n.local/" + UUID.randomUUID());

    triggers.onBoardDisabled(new CrawlEvents.BoardDisabled(boardId, session.userId(), "구조 변경"));

    JsonNode notification = notifications(session).path("notifications").get(0);
    assertThat(notification.path("type").asText()).isEqualTo("board_error");
    assertThat(notification.path("deeplink").asText())
        .isEqualTo("careercompass://boards/" + boardId);
  }

  @Test
  @DisplayName("마감 임박 배치 — 북마크한 공고의 D-3·D-1 만, 하루 한 번·중복 금지 (#47)")
  void dueSoonSweep() {
    Session session = login();
    long boardId =
        TestFixtures.board(jdbc, session.userId(), "https://n.local/" + UUID.randomUUID());
    long d3 = postingWithDue(boardId, "D-3 공고", LocalDate.now().plusDays(3));
    long d1 = postingWithDue(boardId, "D-1 공고", LocalDate.now().plusDays(1));
    long notBookmarked = postingWithDue(boardId, "북마크 안 함", LocalDate.now().plusDays(3));
    bookmark(session.userId(), d3);
    bookmark(session.userId(), d1);

    int first = triggers.dueSoonSweep();
    int second = triggers.dueSoonSweep();

    assertThat(first).isGreaterThanOrEqualTo(2);
    assertThat(second).isZero(); // dedup
    String titles = notifications(session).path("notifications").toString();
    assertThat(titles).contains("D-3").contains("D-1");
    assertThat(titles).doesNotContain("북마크 안 함");
  }

  @Test
  @DisplayName("설정 OFF — 유형 OFF·게시판별 OFF 상태에서는 기록조차 되지 않는다 (#46 #47)")
  void settingsBlockTriggers() {
    Session session = login();
    long boardId =
        TestFixtures.board(jdbc, session.userId(), "https://n.local/" + UUID.randomUUID());
    long postingId = TestFixtures.posting(jdbc, boardId, "차단 대상");

    // 유형 OFF
    call(
        HttpMethod.PUT,
        "/notifications/settings",
        Map.of("newPosting", false),
        session.accessToken());
    triggers.onNewPostings(
        new CrawlEvents.NewPostingsCollected(boardId, session.userId(), List.of(postingId)));
    assertThat(notifications(session).path("notifications")).isEmpty();

    // 유형 ON + 게시판별 OFF
    call(
        HttpMethod.PUT,
        "/notifications/settings",
        Map.of(
            "newPosting", true, "perBoard", List.of(Map.of("boardId", boardId, "enabled", false))),
        session.accessToken());
    triggers.onNewPostings(
        new CrawlEvents.NewPostingsCollected(boardId, session.userId(), List.of(postingId)));
    assertThat(notifications(session).path("notifications")).isEmpty();
  }

  @Test
  @DisplayName("방해 금지 — 발송은 pending 으로 미뤄지고, 해제 후 flush 로 일괄 발송된다 (#46)")
  void quietHoursDeferAndFlush() {
    Session session = login();
    call(
        HttpMethod.POST,
        "/notifications/fcm-token",
        Map.of("deviceId", "dev-quiet", "fcmToken", "fcm-token-quiet"),
        session.accessToken());
    long boardId =
        TestFixtures.board(jdbc, session.userId(), "https://n.local/" + UUID.randomUUID());
    long postingId = TestFixtures.posting(jdbc, boardId, "방해 금지 공고");

    call(
        HttpMethod.PUT,
        "/notifications/settings",
        Map.of("quietHours", Map.of("start", "00:00", "end", "23:59")),
        session.accessToken());
    triggers.onNewPostings(
        new CrawlEvents.NewPostingsCollected(boardId, session.userId(), List.of(postingId)));

    String deferred =
        jdbc.queryForObject(
            "select push_status from notification where user_id = ?",
            String.class,
            session.userId());
    assertThat(deferred).isEqualTo("pending");

    // 방해 금지 해제 후 보류분 발송
    call(
        HttpMethod.PUT,
        "/notifications/settings",
        Map.of("newPosting", true),
        session.accessToken());
    notificationService.flushPending();
    String flushed =
        jdbc.queryForObject(
            "select push_status from notification where user_id = ?",
            String.class,
            session.userId());
    assertThat(flushed).isEqualTo("sent");
  }

  @Test
  @DisplayName("주간 리포트 — ON 사용자에게 지난 7일 요약이 간다 (#46 정의)")
  void weeklyReport() {
    Session session = login();
    long boardId =
        TestFixtures.board(jdbc, session.userId(), "https://n.local/" + UUID.randomUUID());
    TestFixtures.posting(jdbc, boardId, "이번 주 신규");

    triggers.weeklySweep();
    assertThat(notifications(session).path("notifications")).isEmpty(); // 기본 OFF

    call(
        HttpMethod.PUT,
        "/notifications/settings",
        Map.of("weeklyReport", true),
        session.accessToken());
    triggers.weeklySweep();
    JsonNode notification = notifications(session).path("notifications").get(0);
    assertThat(notification.path("type").asText()).isEqualTo("weekly_report");
    assertThat(notification.path("body").asText()).contains("새 공고");
  }

  @Test
  @DisplayName("알림함 — 커서 페이징과 모두 읽음 (#45)")
  void listAndReadAll() {
    Session session = login();
    for (int i = 0; i < 3; i++) {
      notificationService.notify(
          session.userId(),
          "new_posting",
          null,
          "알림 " + i,
          null,
          "careercompass://postings",
          "list-" + i + "-" + UUID.randomUUID());
    }

    JsonNode page1 =
        json(call(HttpMethod.GET, "/notifications?limit=2", null, session.accessToken()))
            .path("data");
    assertThat(page1.path("notifications")).hasSize(2);
    assertThat(page1.path("nextCursor").asText()).isNotBlank();

    call(HttpMethod.POST, "/notifications/read-all", null, session.accessToken());
    JsonNode after = notifications(session);
    for (JsonNode n : after.path("notifications")) {
      assertThat(n.path("isRead").asBoolean()).isTrue();
    }
  }

  @Test
  @DisplayName("FCM 토큰 갱신 — 기기 행에 토큰이 반영된다 (#44 신설 계약)")
  void fcmTokenRefresh() {
    Session session = login();

    call(
        HttpMethod.POST,
        "/notifications/fcm-token",
        Map.of("deviceId", "dev-refresh", "fcmToken", "fcm-refreshed"),
        session.accessToken());

    String token =
        jdbc.queryForObject(
            "select fcm_token from user_device where user_id = ? and device_id = 'dev-refresh'",
            String.class,
            session.userId());
    assertThat(token).isEqualTo("fcm-refreshed");
  }

  private Map<Long, Boolean> perBoard(JsonNode settings) {
    Map<Long, Boolean> out = new java.util.HashMap<>();
    settings
        .path("perBoard")
        .forEach(e -> out.put(e.path("boardId").asLong(), e.path("enabled").asBoolean()));
    return out;
  }

  @Test
  @DisplayName("게시판을 지운 뒤 설정을 조회·저장해도 남은 게시판의 ON/OFF 는 그대로다 (#63)")
  void perBoardSurvivesDeletionAndPartialPut() {
    Session session = login();
    long b1 = TestFixtures.board(jdbc, session.userId(), "https://n.local/" + UUID.randomUUID());
    long b2 = TestFixtures.board(jdbc, session.userId(), "https://n.local/" + UUID.randomUUID());
    long b3 = TestFixtures.board(jdbc, session.userId(), "https://n.local/" + UUID.randomUUID());

    // 조회는 지금 있는 게시판 전부를 낸다 — 기록이 없으면 켜짐
    JsonNode initial =
        json(call(HttpMethod.GET, "/notifications/settings", null, session.accessToken()))
            .path("data");
    assertThat(perBoard(initial))
        .containsEntry(b1, true)
        .containsEntry(b2, true)
        .containsEntry(b3, true);

    call(
        HttpMethod.PUT,
        "/notifications/settings",
        Map.of(
            "perBoard",
            List.of(
                Map.of("boardId", b1, "enabled", false), Map.of("boardId", b2, "enabled", false))),
        session.accessToken());
    call(HttpMethod.DELETE, "/boards/" + b1, null, session.accessToken());

    JsonNode afterDelete =
        json(call(HttpMethod.GET, "/notifications/settings", null, session.accessToken()))
            .path("data");
    assertThat(perBoard(afterDelete))
        .containsOnlyKeys(b2, b3)
        .containsEntry(b2, false)
        .containsEntry(b3, true);

    // b2 를 빼고 b3 만, 그리고 모르는 boardId 하나 — b2 의 OFF 는 지켜지고 모르는 id 는 버려진다
    JsonNode saved =
        json(call(
                HttpMethod.PUT,
                "/notifications/settings",
                Map.of(
                    "perBoard",
                    List.of(
                        Map.of("boardId", b3, "enabled", false),
                        Map.of("boardId", 987654321L, "enabled", false))),
                session.accessToken()))
            .path("data");
    assertThat(perBoard(saved))
        .containsOnlyKeys(b2, b3)
        .containsEntry(b2, false)
        .containsEntry(b3, false);
  }
}
