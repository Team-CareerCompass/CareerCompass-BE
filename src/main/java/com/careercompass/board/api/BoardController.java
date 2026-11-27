package com.careercompass.board.api;

import com.careercompass.board.application.BoardService;
import com.careercompass.board.crawl.BoardStructureDetector;
import com.careercompass.board.domain.Board;
import com.careercompass.common.api.ApiResponse;
import com.careercompass.common.security.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "5. 게시판", description = "API_SPEC §5 — 구조 감지·등록·수집 관리, 20개 상한")
@RestController
@RequestMapping("/boards")
public class BoardController {

  private final BoardService boardService;

  public BoardController(BoardService boardService) {
    this.boardService = boardService;
  }

  public record DetectRequest(@NotBlank String url) {}

  public record DetectResponse(
      String detectStatus, Map<String, String> selectors, List<PreviewRow> preview) {

    record PreviewRow(String title, String url, String date) {}

    static DetectResponse from(BoardStructureDetector.Detection detection) {
      Map<String, String> selectors = null;
      if (detection.selectors() != null) {
        selectors = new LinkedHashMap<>();
        selectors.put("row", detection.selectors().row());
        selectors.put("title", detection.selectors().title());
        selectors.put("link", "href");
        selectors.put("date", detection.selectors().date());
      }
      return new DetectResponse(
          detection.status(),
          selectors,
          detection.preview().stream()
              .map(p -> new PreviewRow(p.title(), p.url(), p.date()))
              .toList());
    }
  }

  @Operation(
      summary = "게시판 구조 자동 감지",
      description =
          "등록 전 미리보기. detectStatus: success/login_required/spa/blocked/failed. 느린 사이트 대응으로 처리 상한 ~30초 — FE 타임아웃은 45초 권장.")
  @PostMapping("/detect")
  public ApiResponse<DetectResponse> detect(
      @CurrentUserId Long userId, @Valid @RequestBody DetectRequest request) {
    return ApiResponse.success(DetectResponse.from(boardService.detect(request.url())));
  }

  public record BoardResponse(
      Long id,
      String url,
      String name,
      String type,
      int cycleHours,
      String status,
      boolean isActive,
      int failCount,
      Instant lastCollectedAt) {

    static BoardResponse from(Board board) {
      return new BoardResponse(
          board.id(),
          board.url(),
          board.name(),
          board.type(),
          board.cycleHours(),
          wireStatus(board),
          board.isActive(),
          board.failCount(),
          board.lastCollectedAt());
    }
  }

  public record CreateRequest(
      @NotBlank String url, @NotBlank String name, @NotBlank String type, Integer cycleHours) {}

  @Operation(summary = "게시판 등록", description = "URL 정규화 후 중복 409, 21번째 422. 등록 즉시 1회 수집 실행.")
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ApiResponse<BoardResponse> create(
      @CurrentUserId Long userId, @Valid @RequestBody CreateRequest request) {
    return ApiResponse.success(
        BoardResponse.from(
            boardService.create(
                userId, request.url(), request.name(), request.type(), request.cycleHours())));
  }

  @Operation(
      summary = "내 게시판 목록",
      description = "status(active|error)·failCount 포함 — 「수집 오류」와 「일시중지」 구분용.")
  @GetMapping
  public ApiResponse<ListResponse> list(@CurrentUserId Long userId) {
    return ApiResponse.success(
        new ListResponse(boardService.list(userId).stream().map(BoardResponse::from).toList()));
  }

  /** FE 는 목록을 {boards: [...]} 로 읽는다. */
  public record ListResponse(List<BoardResponse> boards) {}

  /**
   * 응답 status 는 FE 어휘(active|paused|failed)로 낸다 — 수집 오류(시스템 status=error)는 failed, 사용자가 끈 것은
   * paused. 오류가 우선이다(3연속 실패로 꺼진 게시판은 failed).
   */
  static String wireStatus(Board board) {
    if ("error".equals(board.status())) {
      return "failed";
    }
    return board.isActive() ? "active" : "paused";
  }

  public record PatchRequest(String name, String type, Integer cycleHours, Boolean isActive) {}

  @Operation(summary = "게시판 수정", description = "이름·유형·수집 주기·isActive 부분 수정.")
  @PatchMapping("/{id}")
  public ApiResponse<BoardResponse> patch(
      @CurrentUserId Long userId, @PathVariable Long id, @RequestBody PatchRequest request) {
    return ApiResponse.success(
        BoardResponse.from(
            boardService.patch(
                userId,
                id,
                request.name(),
                request.type(),
                request.cycleHours(),
                request.isActive())));
  }

  @Operation(summary = "게시판 삭제", description = "수집된 공고도 함께 삭제된다.")
  @DeleteMapping("/{id}")
  public ApiResponse<Void> delete(@CurrentUserId Long userId, @PathVariable Long id) {
    boardService.delete(userId, id);
    return ApiResponse.success(null);
  }

  @Operation(summary = "수집 수동 재시도", description = "failCount 초기화·재활성화 후 즉시 1회 수집.")
  @PostMapping("/{id}/retry")
  public ApiResponse<BoardResponse> retry(@CurrentUserId Long userId, @PathVariable Long id) {
    return ApiResponse.success(BoardResponse.from(boardService.retry(userId, id)));
  }
}
