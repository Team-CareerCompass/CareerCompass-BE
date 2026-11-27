package com.careercompass.board.crawl;

import com.careercompass.board.domain.Board;
import com.careercompass.board.domain.BoardRepository;
import com.careercompass.posting.domain.Posting;
import com.careercompass.posting.domain.PostingParsed;
import com.careercompass.posting.domain.PostingParsedRepository;
import com.careercompass.posting.domain.PostingRepository;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 게시판 1곳 수집 (#24, #25) — 목록 파싱 → URL 해시 중복 제거 → 상세 본문 수집 → 저장(+파싱 대기 행). 실패는 런 안에서 3회 재시도(백오프), 런
 * 단위 3연속 실패 시 게시판을 error 로 내리고 이벤트를 낸다.
 */
@Service
public class CrawlService {

  private static final Logger log = LoggerFactory.getLogger(CrawlService.class);
  private static final int MAX_ATTEMPTS = 3;
  private static final int MAX_NEW_PER_RUN = 30;

  private final BoardRepository boardRepository;
  private final PostingRepository postingRepository;
  private final PostingParsedRepository postingParsedRepository;
  private final PageFetcher pageFetcher;
  private final RobotsChecker robotsChecker;
  private final BoardStructureDetector detector;
  private final ApplicationEventPublisher eventPublisher;
  private final io.micrometer.core.instrument.MeterRegistry meterRegistry;
  private final Clock clock;

  public CrawlService(
      BoardRepository boardRepository,
      PostingRepository postingRepository,
      PostingParsedRepository postingParsedRepository,
      PageFetcher pageFetcher,
      RobotsChecker robotsChecker,
      BoardStructureDetector detector,
      ApplicationEventPublisher eventPublisher,
      io.micrometer.core.instrument.MeterRegistry meterRegistry,
      Clock clock) {
    this.boardRepository = boardRepository;
    this.postingRepository = postingRepository;
    this.postingParsedRepository = postingParsedRepository;
    this.pageFetcher = pageFetcher;
    this.robotsChecker = robotsChecker;
    this.detector = detector;
    this.eventPublisher = eventPublisher;
    this.meterRegistry = meterRegistry;
    this.clock = clock;
  }

  /**
   * @return 새로 저장한 공고 수. 실패 시 -1 (failCount 반영됨).
   */
  @Transactional
  public int crawlBoard(Long boardId) {
    Board board = boardRepository.findById(boardId).orElse(null);
    if (board == null) {
      return -1;
    }
    Instant now = clock.instant();
    long startedMs = System.currentTimeMillis();
    try {
      List<Long> newPostingIds = collect(board);
      board.collectSucceeded(now);
      meterRegistry.counter("careercompass.crawl.runs", "outcome", "success").increment();
      log.info(
          "crawl ok: board={} new={} durationMs={}",
          board.id(),
          newPostingIds.size(),
          System.currentTimeMillis() - startedMs);
      if (!newPostingIds.isEmpty()) {
        eventPublisher.publishEvent(
            new CrawlEvents.NewPostingsCollected(board.id(), board.userId(), newPostingIds));
      }
      return newPostingIds.size();
    } catch (Exception e) {
      log.warn("crawl failed: board={} url={} reason={}", board.id(), board.url(), e.toString());
      meterRegistry.counter("careercompass.crawl.runs", "outcome", "failure").increment();
      boolean disabled = board.collectFailed(now);
      if (disabled) {
        eventPublisher.publishEvent(
            new CrawlEvents.BoardDisabled(board.id(), board.userId(), e.getMessage()));
      }
      return -1;
    }
  }

  private List<Long> collect(Board board) {
    URI listUri = URI.create(board.url());
    if (!robotsChecker.allowed(listUri)) {
      throw new IllegalStateException("robots.txt 가 수집을 허용하지 않습니다");
    }
    PageFetcher.FetchedPage page = fetchWithRetry(listUri);
    BoardStructureDetector.Selectors selectors = selectorsOf(board);
    List<BoardStructureDetector.PreviewItem> items = detector.parseList(page.document(), selectors);
    if (items.isEmpty()) {
      // 구조 변경 감지 (#24 A2) — 재감지를 시도하고 그래도 없으면 실패
      BoardStructureDetector.Detection redetected = detector.detect(page.document(), listUri);
      if (!"success".equals(redetected.status())) {
        throw new IllegalStateException("게시판 구조가 변경되어 목록을 읽지 못했습니다");
      }
      items = redetected.preview();
    }

    List<Long> newIds = new ArrayList<>();
    for (BoardStructureDetector.PreviewItem item : items) {
      if (newIds.size() >= MAX_NEW_PER_RUN) {
        log.info("board {}: 신규 {}건 상한 도달, 다음 주기에 계속", board.id(), MAX_NEW_PER_RUN);
        break;
      }
      String normalizedUrl = item.url().split("#", 2)[0];
      String hash = sha256(normalizedUrl);
      if (postingRepository.existsByBoardIdAndUrlHash(board.id(), hash)) {
        continue;
      }
      String rawContent = fetchDetailText(normalizedUrl);
      LocalDate postedDate = BoardStructureDetector.parseDate(item.date()).orElse(null);
      LocalDate dueDate = extractDueDate(rawContent);
      Posting posting =
          postingRepository.save(
              new Posting(
                  board.id(),
                  item.title(),
                  normalizedUrl,
                  hash,
                  rawContent,
                  postingType(board.type()),
                  dueDate,
                  postedDate));
      postingParsedRepository.save(new PostingParsed(posting.id()));
      newIds.add(posting.id());
    }
    return newIds;
  }

  private String fetchDetailText(String url) {
    try {
      URI uri = URI.create(url);
      if (!robotsChecker.allowed(uri)) {
        return null;
      }
      PageFetcher.FetchedPage page = fetchWithRetry(uri);
      return page.document().body() == null ? null : page.document().body().text();
    } catch (Exception e) {
      // 상세 수집 실패는 공고 자체를 버리지 않는다 — 본문 없이 저장
      log.debug("detail fetch failed: {} ({})", url, e.toString());
      return null;
    }
  }

  private PageFetcher.FetchedPage fetchWithRetry(URI uri) {
    RuntimeException last = null;
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      try {
        return pageFetcher.fetch(uri);
      } catch (PageFetcher.FetchException e) {
        last = e;
        if (e.kind() == PageFetcher.FetchException.Kind.BLOCKED
            || e.kind() == PageFetcher.FetchException.Kind.NOT_FOUND) {
          throw e; // 재시도 무의미
        }
        try {
          Thread.sleep(200L * attempt * attempt); // 백오프
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          throw e;
        }
      }
    }
    throw last;
  }

  private BoardStructureDetector.Selectors selectorsOf(Board board) {
    Map<String, Object> stored = board.selectors();
    if (stored == null || stored.get("row") == null) {
      throw new IllegalStateException("저장된 셀렉터가 없습니다 — 게시판 재등록 필요");
    }
    return new BoardStructureDetector.Selectors(
        (String) stored.get("row"), (String) stored.get("title"), (String) stored.get("date"));
  }

  private LocalDate extractDueDate(String rawContent) {
    if (rawContent == null) {
      return null;
    }
    int idx = indexOfAny(rawContent, "마감", "접수기간", "신청기간", "모집기간", "지원기간");
    if (idx < 0) {
      return null;
    }
    String window = rawContent.substring(idx, Math.min(rawContent.length(), idx + 120));
    // 기간 표기의 마지막 날짜를 마감일로 본다
    LocalDate result = null;
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("(20\\d{2})[.\\-/]\\s?(\\d{1,2})[.\\-/]\\s?(\\d{1,2})")
            .matcher(window);
    while (matcher.find()) {
      try {
        result =
            LocalDate.of(
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3)));
      } catch (Exception ignored) {
        // 잘못된 날짜 표기는 건너뛴다
      }
    }
    return result;
  }

  private int indexOfAny(String text, String... keywords) {
    for (String keyword : keywords) {
      int idx = text.indexOf(keyword);
      if (idx >= 0) {
        return idx;
      }
    }
    return -1;
  }

  private String postingType(String boardType) {
    return switch (boardType) {
      case "scholarship", "contest", "recruit", "activity" -> boardType;
      default -> "other";
    };
  }

  private String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
