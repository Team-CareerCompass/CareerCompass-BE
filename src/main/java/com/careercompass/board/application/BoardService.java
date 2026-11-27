package com.careercompass.board.application;

import com.careercompass.board.crawl.BoardStructureDetector;
import com.careercompass.board.crawl.CrawlService;
import com.careercompass.board.crawl.PageFetcher;
import com.careercompass.board.crawl.RobotsChecker;
import com.careercompass.board.crawl.UrlGuard;
import com.careercompass.board.domain.Board;
import com.careercompass.board.domain.BoardRepository;
import com.careercompass.common.api.ErrorCode;
import com.careercompass.common.exception.BusinessException;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 게시판 감지·CRUD (#21~#23). 등록 확정 시 즉시 1회 수집을 동기로 실행한다(F2-1 ⑥). */
@Service
public class BoardService {

  private static final int MAX_BOARDS = 20;
  private static final Set<String> BOARD_TYPES =
      Set.of("scholarship", "recruit", "contest", "activity", "other");

  private final BoardRepository boardRepository;
  private final UrlGuard urlGuard;
  private final RobotsChecker robotsChecker;
  private final PageFetcher pageFetcher;
  private final BoardStructureDetector detector;
  private final CrawlService crawlService;

  public BoardService(
      BoardRepository boardRepository,
      UrlGuard urlGuard,
      RobotsChecker robotsChecker,
      PageFetcher pageFetcher,
      BoardStructureDetector detector,
      CrawlService crawlService) {
    this.boardRepository = boardRepository;
    this.urlGuard = urlGuard;
    this.robotsChecker = robotsChecker;
    this.pageFetcher = pageFetcher;
    this.detector = detector;
    this.crawlService = crawlService;
  }

  /** 감지는 실패도 200 + detectStatus 로 내려간다 (FE 가 상태별 화면을 가른다, #22). */
  public BoardStructureDetector.Detection detect(String rawUrl) {
    URI uri = urlGuard.validateFormat(rawUrl);
    urlGuard.ensurePublicAddress(uri);
    if (!robotsChecker.allowed(uri)) {
      return BoardStructureDetector.Detection.failure("blocked");
    }
    PageFetcher.FetchedPage page;
    try {
      page = pageFetcher.fetch(uri);
    } catch (PageFetcher.FetchException e) {
      return switch (e.kind()) {
        case BLOCKED -> BoardStructureDetector.Detection.failure("blocked");
        default -> BoardStructureDetector.Detection.failure("failed");
      };
    }
    return detector.detect(page.document(), page.finalUri());
  }

  @Transactional
  public Board create(Long userId, String rawUrl, String name, String type, Integer cycleHours) {
    if (type == null || !BOARD_TYPES.contains(type)) {
      throw new BusinessException(
          ErrorCode.INVALID_INPUT,
          "type 은 scholarship/recruit/contest/activity/other 중 하나여야 합니다",
          "type");
    }
    if (name == null || name.isBlank() || name.length() > 100) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "게시판 이름은 1~100자여야 합니다", "name");
    }
    int cycle = cycleHours == null ? 24 : cycleHours;
    if (cycle < 1 || cycle > 24 * 7) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "수집 주기는 1~168시간이어야 합니다", "cycleHours");
    }
    String normalizedUrl = normalize(urlGuard.validateFormat(rawUrl));
    if (boardRepository.existsByUserIdAndUrl(userId, normalizedUrl)) {
      throw new BusinessException(ErrorCode.DUPLICATE_BOARD, "이미 등록된 게시판입니다");
    }
    if (boardRepository.countByUserId(userId) >= MAX_BOARDS) {
      throw new BusinessException(
          ErrorCode.LIMIT_EXCEEDED, "게시판은 최대 " + MAX_BOARDS + "개까지 등록할 수 있습니다", null);
    }

    // 등록 시점에 다시 감지해 셀렉터를 확정 저장한다
    BoardStructureDetector.Detection detection = detect(normalizedUrl);
    if ("blocked".equals(detection.status())) {
      throw new BusinessException(ErrorCode.BOARD_BLOCKED, "자동 수집이 허용되지 않는 게시판입니다");
    }
    if (!"success".equals(detection.status())) {
      throw new BusinessException(
          ErrorCode.INVALID_INPUT, "게시판 구조를 인식하지 못했습니다 (" + detection.status() + ")", "url");
    }

    Map<String, Object> selectors = new HashMap<>();
    selectors.put("row", detection.selectors().row());
    selectors.put("title", detection.selectors().title());
    selectors.put("date", detection.selectors().date());

    Board board =
        boardRepository.save(new Board(userId, normalizedUrl, name, type, cycle, selectors));
    boardRepository.flush();
    // 등록 즉시 1회 수집 (동기 — 실패해도 등록은 유지되고 failCount 에 반영)
    crawlService.crawlBoard(board.id());
    return board;
  }

  @Transactional(readOnly = true)
  public List<Board> list(Long userId) {
    return boardRepository.findAllByUserIdOrderById(userId);
  }

  @Transactional
  public Board patch(
      Long userId, Long boardId, String name, String type, Integer cycleHours, Boolean isActive) {
    if (type != null && !BOARD_TYPES.contains(type)) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "알 수 없는 게시판 유형입니다", "type");
    }
    Board board = owned(userId, boardId);
    board.update(name, type, cycleHours, isActive);
    return board;
  }

  @Transactional
  public void delete(Long userId, Long boardId) {
    boardRepository.delete(owned(userId, boardId)); // 공고는 DB cascade 로 함께 삭제
  }

  /** 수동 재시도 (#25) — 상태 복구 후 즉시 수집. 성공하면 failCount=0 유지. */
  @Transactional
  public Board retry(Long userId, Long boardId) {
    Board board = owned(userId, boardId);
    board.retryRequested();
    boardRepository.flush();
    crawlService.crawlBoard(board.id());
    return boardRepository.findById(boardId).orElseThrow();
  }

  private Board owned(Long userId, Long boardId) {
    return boardRepository
        .findByIdAndUserId(boardId, userId)
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "게시판을 찾을 수 없습니다"));
  }

  /** URL 정규화 (#23) — fragment 제거, 트레일링 슬래시 제거, 호스트 소문자화. 쿼리스트링은 게시판 식별에 필요할 수 있어 유지한다. */
  private String normalize(URI uri) {
    String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
    StringBuilder sb = new StringBuilder(uri.getScheme()).append("://").append(host);
    if (uri.getPort() > 0
        && !(uri.getPort() == 80 && uri.getScheme().equals("http"))
        && !(uri.getPort() == 443 && uri.getScheme().equals("https"))) {
      sb.append(':').append(uri.getPort());
    }
    String path = uri.getRawPath() == null ? "" : uri.getRawPath();
    while (path.endsWith("/")) {
      path = path.substring(0, path.length() - 1);
    }
    sb.append(path);
    if (uri.getRawQuery() != null && !uri.getRawQuery().isEmpty()) {
      sb.append('?').append(uri.getRawQuery());
    }
    return sb.toString();
  }
}
