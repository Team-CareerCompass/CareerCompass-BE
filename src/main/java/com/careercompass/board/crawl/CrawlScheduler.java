package com.careercompass.board.crawl;

import com.careercompass.board.domain.Board;
import com.careercompass.board.domain.BoardRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 주기 수집 (#24) — 1분마다 스캔해서 cycleHours 가 지난 게시판을 워커 풀(5)로 수집한다. 한 게시판이 다른 게시판을 막지 않는다(게시판 단위 병렬, 도메인
 * 단위 직렬은 PageFetcher 가 보장). 단일 인스턴스 배포 전제 — 다중 인스턴스로 가면 분산 락(ShedLock 등)을 붙인다.
 */
@Component
@ConditionalOnProperty(
    name = "app.crawl.scheduler-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class CrawlScheduler {

  private static final Logger log = LoggerFactory.getLogger(CrawlScheduler.class);

  private final BoardRepository boardRepository;
  private final CrawlService crawlService;
  private final Clock clock;
  // 동시 워커 5 (보고서 비기능 요구)
  private final ExecutorService workers = Executors.newFixedThreadPool(5);

  public CrawlScheduler(BoardRepository boardRepository, CrawlService crawlService, Clock clock) {
    this.boardRepository = boardRepository;
    this.crawlService = crawlService;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${app.crawl.scan-interval-ms:60000}")
  public void scan() {
    Instant now = clock.instant();
    List<Board> due =
        boardRepository.findAllByIsActiveTrueAndStatus("active").stream()
            .filter(
                board ->
                    board.lastCollectedAt() == null
                        || board
                            .lastCollectedAt()
                            .plus(Duration.ofHours(board.cycleHours()))
                            .isBefore(now))
            .toList();
    for (Board board : due) {
      Long boardId = board.id();
      workers.submit(
          () -> {
            try {
              crawlService.crawlBoard(boardId);
            } catch (Exception e) {
              log.error("scheduled crawl error: board={}", boardId, e);
            }
          });
    }
  }
}
