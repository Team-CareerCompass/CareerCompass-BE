package com.careercompass.application.application;

import com.careercompass.analysis.gateway.LlmGateway;
import com.careercompass.application.domain.Application;
import com.careercompass.application.domain.ApplicationItem;
import com.careercompass.application.domain.ApplicationItemRepository;
import com.careercompass.application.domain.ApplicationRepository;
import com.careercompass.common.api.ErrorCode;
import com.careercompass.common.exception.BusinessException;
import com.careercompass.posting.domain.Posting;
import com.careercompass.posting.domain.PostingRepository;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 초안 생성 워커 (#36, #37) — 항목별 독립 생성: 하나가 실패해도 나머지는 나간다(partial_failed). 항목 완료마다 SSE 로 즉시 알린다. 기본
 * 400~600자(제한 없을 때)는 게이트웨이 계약.
 */
@Component
public class DraftGenerationWorker {

  private static final Logger log = LoggerFactory.getLogger(DraftGenerationWorker.class);

  private final ApplicationRepository applicationRepository;
  private final ApplicationItemRepository itemRepository;
  private final PostingRepository postingRepository;
  private final DraftContextBuilder contextBuilder;
  private final LlmGateway llmGateway;
  private final SseHub sseHub;
  private final ApplicationService applicationService;

  public DraftGenerationWorker(
      ApplicationRepository applicationRepository,
      ApplicationItemRepository itemRepository,
      PostingRepository postingRepository,
      DraftContextBuilder contextBuilder,
      LlmGateway llmGateway,
      SseHub sseHub,
      ApplicationService applicationService) {
    this.applicationRepository = applicationRepository;
    this.itemRepository = itemRepository;
    this.postingRepository = postingRepository;
    this.contextBuilder = contextBuilder;
    this.llmGateway = llmGateway;
    this.sseHub = sseHub;
    this.applicationService = applicationService;
  }

  /** 지금 이 서버에서 생성이 도는 지원서 — 멱등 재호출(#57)이 같은 항목을 두 번 생성(LLM 비용 2배)하지 않게 막는다. */
  private final java.util.Set<Long> inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet();

  /** 재생성 요청의 옵션 — 항목 id 별로 워커가 꺼내 쓴다. */
  private record RegenRequest(String tone, List<Long> emphasizeCardIds) {}

  private final Map<Long, RegenRequest> regenRequests =
      new java.util.concurrent.ConcurrentHashMap<>();

  /**
   * 비동기 생성. 같은 지원서에 이미 돌고 있으면 그쪽이 새 loading 항목까지 이어 처리한다. 끝난 뒤에도 그 사이 생긴 loading 항목(재생성)이 있으면 다시 돈다
   * — 재생성 표시(커밋)가 이 호출보다 먼저 일어나므로 빠지는 항목이 없다.
   */
  @Async
  public void generateAsync(Long applicationId) {
    for (int attempt = 0; attempt < 3; attempt++) {
      if (!inFlight.add(applicationId)) {
        return; // 이미 생성 중 — 도는 쪽이 이어 처리한다
      }
      try {
        generate(applicationId);
      } finally {
        inFlight.remove(applicationId);
      }
      if (!needsGeneration(applicationId)) {
        return;
      }
    }
  }

  private boolean needsGeneration(Long applicationId) {
    return applicationRepository
        .findById(applicationId)
        .filter(Application::generating)
        .map(
            app ->
                itemRepository.findAllByApplicationIdOrderByItemOrder(applicationId).stream()
                    .anyMatch(item -> "loading".equals(item.status())))
        .orElse(false);
  }

  private void generate(Long applicationId) {
    // 도는 중에 재생성으로 새 loading 항목이 생길 수 있어 남은 것이 없을 때까지 돈다
    for (int round = 0; round < 10; round++) {
      Application application = applicationRepository.findById(applicationId).orElse(null);
      if (application == null || !application.generating()) {
        return;
      }
      Posting posting = postingRepository.findById(application.postingId()).orElse(null);
      if (posting == null) {
        return;
      }
      List<ApplicationItem> loading =
          itemRepository.findAllByApplicationIdOrderByItemOrder(applicationId).stream()
              .filter(item -> "loading".equals(item.status()))
              .toList();
      if (loading.isEmpty()) {
        // 이전 실행(재시작 전)에서 실패한 항목도 있을 수 있으니 결과는 행 상태로 센다
        boolean allDone =
            itemRepository.findAllByApplicationIdOrderByItemOrder(applicationId).stream()
                .allMatch(item -> "done".equals(item.status()));
        application.generationFinished(allDone);
        applicationRepository.save(application);
        sseHub.complete(applicationId, application.status());
        return;
      }
      DraftContextBuilder.Context context = contextBuilder.build(application.userId(), posting);
      for (ApplicationItem item : loading) {
        RegenRequest regen = regenRequests.remove(item.id());
        String tone = regen == null || regen.tone() == null ? application.tone() : regen.tone();
        draftSingle(
            application,
            item,
            context,
            tone,
            regen == null ? null : regen.emphasizeCardIds(),
            regen != null);
      }
    }
  }

  /**
   * 재생성 준비 (#38) — 항목을 loading 으로, 지원서를 generating 으로 돌리고 옵션을 맡긴다. 생성은 {@link #generateAsync} 가 한다.
   * 성공하면 이전 답이 버전으로 보관되고, 실패하면 이전 답은 그대로 두고 항목만 failed 가 된다.
   */
  public ApplicationItem prepareRegenerate(
      Long userId, Long applicationId, Long itemId, String tone, List<Long> emphasizeCardIds) {
    if (tone != null && !ApplicationService.TONES.contains(tone)) {
      throw new BusinessException(
          ErrorCode.INVALID_INPUT, "tone 은 formal/casual/confident 중 하나여야 합니다", "tone");
    }
    regenRequests.put(itemId, new RegenRequest(tone, emphasizeCardIds));
    try {
      return applicationService.markRegenerating(userId, applicationId, itemId);
    } catch (RuntimeException e) {
      regenRequests.remove(itemId);
      throw e;
    }
  }

  /** 수동 추가 항목의 초안 생성 (#36) — 실패하면 failed 로 남고 재생성으로 재시도한다. */
  public ApplicationItem draftNewItem(Long userId, Long applicationId, Long itemId) {
    Application application = applicationService.get(userId, applicationId).application();
    ApplicationItem item = applicationService.getItem(userId, applicationId, itemId);
    Posting posting = postingRepository.findById(application.postingId()).orElseThrow();
    DraftContextBuilder.Context context = contextBuilder.build(userId, posting);
    draftSingle(application, item, context, application.tone(), null, false);
    return itemRepository.findById(item.id()).orElse(item);
  }

  /** 단일 항목 생성 — 초기 일괄 생성과 재생성이 쓰는 공통 경로. 재생성이면 성공 시에만 이전 답을 버전으로 보관한다. */
  public boolean draftSingle(
      Application application,
      ApplicationItem item,
      DraftContextBuilder.Context context,
      String tone,
      List<Long> emphasizeCardIds,
      boolean archivePrevious) {
    String previous = item.answer();
    try {
      List<String> summaries =
          contextBuilder.summariesFor(
              application.userId(), context, item.question(), emphasizeCardIds);
      String answer =
          llmGateway.draftAnswer(
              item.question(),
              item.maxChars(),
              tone,
              context.postingTitle(),
              context.keywords(),
              summaries);
      if (archivePrevious) {
        applicationService.archiveVersion(item.id(), previous, application.tone());
      }
      item.draftReady(answer);
      itemRepository.save(item);
      sseHub.publish(
          application.id(),
          new SseHub.Event("item_done", Map.of("itemId", item.id(), "answer", answer)));
      return true;
    } catch (BusinessException e) {
      if (e.code() != ErrorCode.LLM_UNAVAILABLE) {
        throw e;
      }
      return failItem(application, item, ErrorCode.LLM_UNAVAILABLE);
    } catch (Exception e) {
      log.warn("초안 생성 실패: item={}", item.id(), e);
      return failItem(application, item, ErrorCode.INTERNAL_ERROR);
    }
  }

  /** 항목 실패 — status=failed 로 남기고 item_failed{itemId, code} 를 보낸다 (#59). code 는 §9 값. */
  private boolean failItem(Application application, ApplicationItem item, ErrorCode code) {
    item.draftFailed();
    itemRepository.save(item);
    sseHub.publish(
        application.id(),
        new SseHub.Event("item_failed", Map.of("itemId", item.id(), "code", code.name())));
    return false;
  }
}
