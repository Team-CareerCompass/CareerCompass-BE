package com.careercompass.notification.application;

import com.careercompass.board.crawl.CrawlEvents;
import com.careercompass.board.domain.Board;
import com.careercompass.board.domain.BoardRepository;
import com.careercompass.posting.domain.Posting;
import com.careercompass.posting.domain.PostingRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 발송 트리거 3종 (F2-4, #47) — 신규 공고(묶음)·마감 D-3/D-1(북마크 대상, 일 배치)·수집 오류(3연속 실패). 설정·방해 금지 검사는
 * NotificationService.notify 한 곳에서 이뤄진다.
 */
@Component
public class NotificationTriggers {

  private static final ZoneId KST = ZoneId.of("Asia/Seoul");

  private final NotificationService notificationService;
  private final BoardRepository boardRepository;
  private final PostingRepository postingRepository;
  private final NamedParameterJdbcTemplate jdbc;
  private final Clock clock;

  public NotificationTriggers(
      NotificationService notificationService,
      BoardRepository boardRepository,
      PostingRepository postingRepository,
      NamedParameterJdbcTemplate jdbc,
      Clock clock) {
    this.notificationService = notificationService;
    this.boardRepository = boardRepository;
    this.postingRepository = postingRepository;
    this.jdbc = jdbc;
    this.clock = clock;
  }

  /** 신규 공고 — 수집 주기당 한 건으로 묶어 보낸다(폭탄 방지). */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onNewPostings(CrawlEvents.NewPostingsCollected event) {
    Board board = boardRepository.findById(event.boardId()).orElse(null);
    if (board == null || event.postingIds().isEmpty()) {
      return;
    }
    int count = event.postingIds().size();
    Long firstId = event.postingIds().get(0);
    String firstTitle = postingRepository.findById(firstId).map(Posting::title).orElse("새 공고");
    String title = count == 1 ? "새 공고: " + firstTitle : board.name() + "에 새 공고 " + count + "건";
    String deeplink =
        count == 1
            ? "careercompass://postings/" + firstId
            : "careercompass://postings?boardId=" + board.id();
    notificationService.notify(
        event.userId(),
        "new_posting",
        board.id(),
        title,
        count == 1 ? board.name() : firstTitle + " 외 " + (count - 1) + "건",
        deeplink,
        "new:" + board.id() + ":" + firstId);
  }

  /** 수집 오류 — 3연속 실패로 게시판이 꺼졌을 때 (#25 연동). */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onBoardDisabled(CrawlEvents.BoardDisabled event) {
    Board board = boardRepository.findById(event.boardId()).orElse(null);
    if (board == null) {
      return;
    }
    notificationService.notify(
        event.userId(),
        "board_error",
        null, // 게시판별 OFF 는 신규 공고용 — 오류 알림은 게시판 설정과 무관하게 나간다
        "게시판 수집이 중단되었습니다",
        board.name() + " — 3회 연속 실패로 수집을 멈췄습니다. 재시도하거나 다시 등록해 주세요.",
        "careercompass://boards/" + board.id(),
        "board_error:" + board.id() + ":" + LocalDate.now(clock.withZone(KST)));
  }

  /** 마감 임박 — 북마크한 공고의 D-3·D-1, 하루 한 번 배치. dedupKey 로 재발송을 막는다. */
  public int dueSoonSweep() {
    LocalDate today = LocalDate.now(clock.withZone(KST));
    int sent = 0;
    for (int dday : new int[] {3, 1}) {
      LocalDate target = today.plusDays(dday);
      List<Map<String, Object>> rows =
          jdbc.queryForList(
              """
              select st.user_id, p.id as posting_id, p.title, p.board_id
              from posting_user_state st
              join posting p on p.id = st.posting_id
              where st.is_bookmarked and p.due_date = :target
              """,
              Map.of("target", target));
      for (Map<String, Object> row : rows) {
        long userId = ((Number) row.get("user_id")).longValue();
        long postingId = ((Number) row.get("posting_id")).longValue();
        boolean notified =
            notificationService.notify(
                userId,
                "due_soon",
                null,
                "마감 D-" + dday + ": " + row.get("title"),
                "북마크한 공고의 마감이 " + dday + "일 남았습니다",
                "careercompass://postings/" + postingId,
                "due:" + postingId + ":d" + dday);
        if (notified) {
          sent++;
        }
      }
    }
    return sent;
  }

  /** 주간 리포트 (#46 정의) — 지난 7일 신규 공고 수·7일 내 마감 예정 수 요약. weeklyReport ON 사용자만. */
  public int weeklySweep() {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            """
            select b.user_id,
                   count(*) filter (where p.collected_at >= now() - interval '7 days') as new_count,
                   count(*) filter (where p.due_date between current_date and current_date + 7)
                     as due_count
            from board b
            join posting p on p.board_id = b.id
            group by b.user_id
            """,
            Map.of());
    LocalDate today = LocalDate.now(clock.withZone(KST));
    int sent = 0;
    for (Map<String, Object> row : rows) {
      long userId = ((Number) row.get("user_id")).longValue();
      long newCount = ((Number) row.get("new_count")).longValue();
      long dueCount = ((Number) row.get("due_count")).longValue();
      if (newCount == 0 && dueCount == 0) {
        continue;
      }
      boolean notified =
          notificationService.notify(
              userId,
              "weekly_report",
              null,
              "주간 리포트",
              "지난 7일 새 공고 " + newCount + "건, 7일 내 마감 예정 " + dueCount + "건",
              "careercompass://postings",
              "weekly:" + today);
      if (notified) {
        sent++;
      }
    }
    return sent;
  }
}
