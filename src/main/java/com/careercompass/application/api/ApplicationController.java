package com.careercompass.application.api;

import com.careercompass.application.application.ApplicationService;
import com.careercompass.application.application.DraftGenerationWorker;
import com.careercompass.application.application.SseHub;
import com.careercompass.application.domain.Application;
import com.careercompass.application.domain.ApplicationItem;
import com.careercompass.application.domain.ApplicationItemVersion;
import com.careercompass.common.api.ApiResponse;
import com.careercompass.common.api.ErrorCode;
import com.careercompass.common.security.CurrentUserId;
import com.careercompass.posting.domain.Posting;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Tag(name = "6. 지원서", description = "API_SPEC §6 — AI 초안 생성·SSE·재생성/버전·저장·이력")
@RestController
@RequestMapping("/applications")
public class ApplicationController {

  private final ApplicationService service;
  private final DraftGenerationWorker worker;
  private final SseHub sseHub;

  public ApplicationController(
      ApplicationService service, DraftGenerationWorker worker, SseHub sseHub) {
    this.service = service;
    this.worker = worker;
    this.sseHub = sseHub;
  }

  /**
   * 공고가 글자 수 제한을 밝히지 않은 문항(저장값 null)의 응답 값 — 서버 기본 생성 길이 400~600자의 상한. FE 는 maxChars 를 양수로만
   * 받는다(null 이면 지원서 전체 파싱 실패). 저장값은 null 로 남겨 「제한 없음」을 기억한다.
   */
  static final int DEFAULT_MAX_CHARS = 600;

  public record ItemResponse(
      Long id, int order, String question, int maxChars, String status, String answer) {

    static ItemResponse from(ApplicationItem item) {
      return new ItemResponse(
          item.id(),
          item.itemOrder(),
          item.question(),
          item.maxChars() == null || item.maxChars() < 1 ? DEFAULT_MAX_CHARS : item.maxChars(),
          item.status(),
          item.answer());
    }
  }

  public record ApplicationResponse(
      Long id,
      Long postingId,
      String postingTitle,
      String postingType,
      String status,
      String tone,
      String result,
      Integer scoreSnapshot,
      Instant createdAt,
      Instant savedAt,
      List<ItemResponse> items) {}

  private ApplicationResponse toResponse(ApplicationService.ApplicationWithItems pair) {
    Application application = pair.application();
    Posting posting = service.postingOf(application);
    return new ApplicationResponse(
        application.id(),
        application.postingId(),
        posting == null ? null : posting.title(),
        posting == null ? null : posting.type(),
        application.status(),
        application.tone(),
        application.result(),
        application.scoreSnapshot(),
        application.createdAt(),
        application.savedAt(),
        pair.items().stream().map(ItemResponse::from).toList());
  }

  public record CreateRequest(
      @NotNull Long postingId, String tone, List<ApplicationService.ItemInput> items) {}

  @Operation(
      summary = "지원서 생성 (AI 초안 트리거) — 공고당 멱등",
      description =
          "장학금·채용 공고만 가능. 같은 공고에 진행 중(generating·ready·partial_failed) 지원서가 있으면 새로 만들지 않고 그것을 200 으로"
              + " 돌려준다(#57). 새로 만들면 201. items 를 실으면 그 문항이 정본이다(#58). 항목은 비동기로 생성되며 진행은 SSE(/stream)"
              + " 또는 단건 조회로 확인.")
  @PostMapping
  public ResponseEntity<ApiResponse<ApplicationResponse>> create(
      @CurrentUserId Long userId, @Valid @RequestBody CreateRequest request) {
    ApplicationService.CreateResult result;
    try {
      result = service.create(userId, request.postingId(), request.tone(), request.items());
    } catch (DataIntegrityViolationException race) {
      // 같은 공고로 동시에 두 요청이 왔다 — 먼저 만든 쪽을 돌려준다 (#57, uk_application_in_progress)
      result =
          service
              .inProgress(userId, request.postingId())
              .map(pair -> new ApplicationService.CreateResult(pair, true))
              .orElseThrow(() -> race);
    }
    ApplicationService.ApplicationWithItems pair = result.pair();
    // 재사용된 지원서라도 생성이 멈춰 있으면(서버 재시작 등) 다시 돌린다. 이미 도는 중이면 워커가 무시한다.
    if (pair.application().generating() && !pair.items().isEmpty()) {
      worker.generateAsync(pair.application().id());
    }
    return ResponseEntity.status(result.reused() ? HttpStatus.OK : HttpStatus.CREATED)
        .body(ApiResponse.success(toResponse(pair)));
  }

  /** FE 는 이력을 {applications: [...], nextCursor} 로 읽는다. */
  public record HistoryResponse(List<ApplicationResponse> applications, String nextCursor) {}

  @Operation(
      summary = "지원 이력 목록",
      description = "status 필터·커서 페이징(cursor·limit, 최신순). 공고 제목·유형·적합도 스냅샷·결과 포함.")
  @GetMapping
  public ApiResponse<HistoryResponse> list(
      @CurrentUserId Long userId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    ApplicationService.HistoryPage page =
        service.list(
            userId, status, cursor, com.careercompass.common.paging.PageLimit.resolve(limit));
    return ApiResponse.success(
        new HistoryResponse(
            page.applications().stream().map(this::toResponse).toList(), page.nextCursor()));
  }

  @Operation(summary = "지원서 단건 조회", description = "SSE 가 끊겼을 때의 상태 복원 경로 (v0.2 신설, #37).")
  @GetMapping("/{id}")
  public ApiResponse<ApplicationResponse> get(@CurrentUserId Long userId, @PathVariable Long id) {
    return ApiResponse.success(toResponse(service.get(userId, id)));
  }

  @Operation(
      summary = "생성 진행 SSE 스트림",
      description =
          "item_done/item_failed/complete 이벤트. 구독 시 이미 끝난 항목의 스냅샷을 먼저 보낸다(재연결 복원). 15초 heartbeat.")
  @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(@CurrentUserId Long userId, @PathVariable Long id) {
    ApplicationService.ApplicationWithItems pair = service.get(userId, id); // 남의 스트림 차단
    List<SseHub.Event> snapshot = new ArrayList<>();
    for (ApplicationItem item : pair.items()) {
      if ("done".equals(item.status())) {
        snapshot.add(
            new SseHub.Event(
                "item_done",
                Map.of("itemId", item.id(), "answer", item.answer() == null ? "" : item.answer())));
      } else if ("failed".equals(item.status())) {
        // 실패 원인은 행에 남기지 않으므로 복원 스냅샷의 code 는 LLM_UNAVAILABLE 로 고정한다 (#59)
        snapshot.add(
            new SseHub.Event(
                "item_failed",
                Map.of("itemId", item.id(), "code", ErrorCode.LLM_UNAVAILABLE.name())));
      }
    }
    return sseHub.subscribe(
        id, snapshot, pair.application().generating(), pair.application().status());
  }

  public record AddItemRequest(@NotBlank String question, Integer maxChars) {}

  @Operation(
      summary = "질문 항목 직접 추가",
      description = "양식 미인식 공고용 (v0.2 신설, #36). 추가 직후 AI 초안을 동기 생성한다.")
  @PostMapping("/{id}/items")
  @ResponseStatus(HttpStatus.CREATED)
  public ApiResponse<ItemResponse> addItem(
      @CurrentUserId Long userId,
      @PathVariable Long id,
      @Valid @RequestBody AddItemRequest request) {
    ApplicationItem item = service.addItem(userId, id, request.question(), request.maxChars());
    return ApiResponse.success(ItemResponse.from(worker.draftNewItem(userId, id, item.id())));
  }

  public record RegenerateRequest(String tone, List<Long> emphasizeCardIds) {}

  @Operation(
      summary = "항목 재생성",
      description = "성공 시에만 이전 답변을 버전으로 보관(최대 3개). LLM 장애면 503 과 함께 기존 답변 유지 — 재시도 가능.")
  @PostMapping("/{id}/items/{itemId}/regenerate")
  public ApiResponse<ItemResponse> regenerate(
      @CurrentUserId Long userId,
      @PathVariable Long id,
      @PathVariable Long itemId,
      @RequestBody(required = false) RegenerateRequest request) {
    String tone = request == null ? null : request.tone();
    List<Long> emphasize = request == null ? null : request.emphasizeCardIds();
    worker.prepareRegenerate(userId, id, itemId, tone, emphasize);
    worker.generateAsync(id);
    // 앱의 읽기 타임아웃(10초)보다 짧게만 기다린다. 그 안에 끝나면 done(또는 failed), 아니면 loading 으로 답하고
    // 생성은 계속된다 — 앱은 loading 이면 POST /applications 를 다시 불러 스트림으로 받는다.
    long deadline = System.currentTimeMillis() + REGENERATE_WAIT_MS;
    ApplicationItem item = service.getItem(userId, id, itemId);
    while ("loading".equals(item.status()) && System.currentTimeMillis() < deadline) {
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
      item = service.getItem(userId, id, itemId);
    }
    return ApiResponse.success(ItemResponse.from(item));
  }

  /** 재생성 응답을 기다리는 상한 — FE read 타임아웃 10초보다 여유 있게 짧게. */
  static final long REGENERATE_WAIT_MS = 7_000;

  public record EditItemRequest(String answer) {}

  @Operation(summary = "항목 수동 편집 (임시 저장 겸용)", description = "글자 수 초과여도 저장된다. 충돌은 마지막 쓰기 우선.")
  @PatchMapping("/{id}/items/{itemId}")
  public ApiResponse<ItemResponse> editItem(
      @CurrentUserId Long userId,
      @PathVariable Long id,
      @PathVariable Long itemId,
      @RequestBody EditItemRequest request) {
    return ApiResponse.success(
        ItemResponse.from(service.editItem(userId, id, itemId, request.answer())));
  }

  public record VersionResponse(Long id, String answer, String tone, Instant createdAt) {

    static VersionResponse from(ApplicationItemVersion version) {
      return new VersionResponse(
          version.id(), version.answer(), version.tone(), version.createdAt());
    }
  }

  @Operation(summary = "항목 버전 목록", description = "최신순, 최대 3개 (v0.2 신설, #38).")
  @GetMapping("/{id}/items/{itemId}/versions")
  public ApiResponse<List<VersionResponse>> versions(
      @CurrentUserId Long userId, @PathVariable Long id, @PathVariable Long itemId) {
    return ApiResponse.success(
        service.versions(userId, id, itemId).stream().map(VersionResponse::from).toList());
  }

  @Operation(summary = "버전 되돌리기", description = "현재 본문도 버전으로 보관한 뒤 선택한 버전으로 되돌린다 (v0.2 신설, #38).")
  @PostMapping({
    "/{id}/items/{itemId}/versions/{versionId}/revert",
    "/{id}/items/{itemId}/versions/{versionId}/restore" // FE canon 이름 (v0.3 별칭)
  })
  public ApiResponse<ItemResponse> revert(
      @CurrentUserId Long userId,
      @PathVariable Long id,
      @PathVariable Long itemId,
      @PathVariable Long versionId) {
    return ApiResponse.success(ItemResponse.from(service.revert(userId, id, itemId, versionId)));
  }

  @Operation(summary = "최종 저장", description = "status=saved + 적합도 스냅샷 기록. 지원 이력에 자동 반영. 멱등.")
  @PostMapping("/{id}/save")
  public ApiResponse<ApplicationResponse> save(@CurrentUserId Long userId, @PathVariable Long id) {
    service.save(userId, id);
    return ApiResponse.success(toResponse(service.get(userId, id)));
  }

  public record ResultRequest(@NotBlank String result) {}

  @Operation(summary = "지원 결과 입력", description = "pending/pass/fail/none. 언제든 수정 가능.")
  @PatchMapping("/{id}/result")
  public ApiResponse<ApplicationResponse> result(
      @CurrentUserId Long userId,
      @PathVariable Long id,
      @Valid @RequestBody ResultRequest request) {
    service.updateResult(userId, id, request.result());
    return ApiResponse.success(toResponse(service.get(userId, id)));
  }

  @Operation(summary = "이력 삭제", description = "연결된 지원서(항목·버전 포함)가 함께 삭제된다.")
  @DeleteMapping("/{id}")
  public ApiResponse<Void> delete(@CurrentUserId Long userId, @PathVariable Long id) {
    service.delete(userId, id);
    return ApiResponse.success(null);
  }
}
