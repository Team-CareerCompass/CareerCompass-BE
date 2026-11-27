package com.careercompass.application.application;

import com.careercompass.analysis.domain.SuitabilityScoreRepository;
import com.careercompass.application.domain.Application;
import com.careercompass.application.domain.ApplicationItem;
import com.careercompass.application.domain.ApplicationItemRepository;
import com.careercompass.application.domain.ApplicationItemVersion;
import com.careercompass.application.domain.ApplicationItemVersionRepository;
import com.careercompass.application.domain.ApplicationRepository;
import com.careercompass.board.domain.BoardRepository;
import com.careercompass.common.api.ErrorCode;
import com.careercompass.common.exception.BusinessException;
import com.careercompass.posting.domain.Posting;
import com.careercompass.posting.domain.PostingParsed;
import com.careercompass.posting.domain.PostingParsedRepository;
import com.careercompass.posting.domain.PostingRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 지원서 생성·편집·이력 (§6, #36~#40). 편집 충돌은 「마지막 쓰기 우선」(30초 자동 임시 저장 전제, #39 결정). */
@Service
public class ApplicationService {

  public static final Set<String> TONES = Set.of("formal", "casual", "confident");
  private static final Set<String> GENERATABLE_TYPES = Set.of("scholarship", "recruit");
  private static final int MAX_VERSIONS = 3;

  private final ApplicationRepository applicationRepository;
  private final ApplicationItemRepository itemRepository;
  private final ApplicationItemVersionRepository versionRepository;
  private final PostingRepository postingRepository;
  private final PostingParsedRepository parsedRepository;
  private final BoardRepository boardRepository;
  private final SuitabilityScoreRepository scoreRepository;
  private final Clock clock;

  public ApplicationService(
      ApplicationRepository applicationRepository,
      ApplicationItemRepository itemRepository,
      ApplicationItemVersionRepository versionRepository,
      PostingRepository postingRepository,
      PostingParsedRepository parsedRepository,
      BoardRepository boardRepository,
      SuitabilityScoreRepository scoreRepository,
      Clock clock) {
    this.applicationRepository = applicationRepository;
    this.itemRepository = itemRepository;
    this.versionRepository = versionRepository;
    this.postingRepository = postingRepository;
    this.parsedRepository = parsedRepository;
    this.boardRepository = boardRepository;
    this.scoreRepository = scoreRepository;
    this.clock = clock;
  }

  public record ApplicationWithItems(Application application, List<ApplicationItem> items) {}

  /** POST /applications 의 결과. reused 면 같은 공고의 진행 중 지원서를 돌려준 것이다(200, #57). */
  public record CreateResult(ApplicationWithItems pair, boolean reused) {}

  /** 사용자가 확인·수정한 문항 (#58). order 가 없으면 배열 순서를 쓴다. maxChars null = 공고가 제한을 밝히지 않음. */
  public record ItemInput(Integer order, String question, Integer maxChars) {}

  /** 진행 중으로 보는 상태 — 이 상태의 지원서가 있으면 새로 만들지 않는다 (#57). */
  public static final List<String> IN_PROGRESS = List.of("generating", "ready", "partial_failed");

  static final int MAX_ITEMS = 20;
  static final int MAX_QUESTION_CHARS = 500;
  static final int MAX_MAX_CHARS = 5000;

  /**
   * 지원서 생성 — 공고당 멱등 (#57). 같은 사용자의 같은 공고에 진행 중 지원서가 있으면 새로 만들지 않고 그것을 돌려준다(이때 tone·items 는 무시된다).
   * saved 만 있으면 새로 만든다. items 가 오면 그것이 문항의 정본이고 파싱 결과를 다시 읽지 않는다 (#58).
   */
  @Transactional
  public CreateResult create(Long userId, Long postingId, String tone, List<ItemInput> items) {
    String resolvedTone = tone == null ? "formal" : tone;
    if (!TONES.contains(resolvedTone)) {
      throw new BusinessException(
          ErrorCode.INVALID_INPUT, "tone 은 formal/casual/confident 중 하나여야 합니다", "tone");
    }
    List<ItemInput> normalized = items == null ? null : normalizeItems(items);
    Posting posting = ownedPosting(userId, postingId);
    if (!GENERATABLE_TYPES.contains(posting.type())) {
      // 명세서 F3-3 — 장학금·채용만 지원서 작성 가능 (공모전·대외활동은 지원 방식이 다양)
      throw new BusinessException(
          ErrorCode.INVALID_INPUT, "장학금·채용 공고에서만 지원서를 생성할 수 있습니다", "postingId");
    }

    Optional<Application> inProgress = findInProgress(userId, postingId);
    if (inProgress.isPresent()) {
      Application existing = inProgress.get();
      return new CreateResult(
          new ApplicationWithItems(
              existing, itemRepository.findAllByApplicationIdOrderByItemOrder(existing.id())),
          true);
    }

    // saveAndFlush — 동시 요청이 부분 유니크 인덱스(uk_application_in_progress)에 걸리면 여기서 바로 드러나게 한다
    Application application =
        applicationRepository.saveAndFlush(new Application(userId, postingId, resolvedTone));

    List<ItemInput> questions = normalized != null ? normalized : parsedQuestions(postingId);
    List<ApplicationItem> created = new ArrayList<>();
    int order = 1;
    for (ItemInput question : questions) {
      created.add(
          itemRepository.save(
              new ApplicationItem(
                  application.id(), order++, question.question(), question.maxChars())));
    }
    if (created.isEmpty()) {
      // 양식 미인식 — 항목 없이 시작, 사용자가 직접 추가한다 (#36 계약)
      application.generationFinished(true);
    }
    return new CreateResult(new ApplicationWithItems(application, created), false);
  }

  /** 동시 생성 경합에서 진 요청이 이긴 쪽 지원서를 돌려받는 경로 (#57). */
  @Transactional(readOnly = true)
  public Optional<ApplicationWithItems> inProgress(Long userId, Long postingId) {
    return findInProgress(userId, postingId)
        .map(
            app ->
                new ApplicationWithItems(
                    app, itemRepository.findAllByApplicationIdOrderByItemOrder(app.id())));
  }

  private Optional<Application> findInProgress(Long userId, Long postingId) {
    return applicationRepository
        .findFirstByUserIdAndPostingIdAndStatusInAndDeletedAtIsNullOrderByIdDesc(
            userId, postingId, IN_PROGRESS);
  }

  private List<ItemInput> parsedQuestions(Long postingId) {
    List<ItemInput> questions = new ArrayList<>();
    PostingParsed parsed = parsedRepository.findById(postingId).orElse(null);
    if (parsed != null && parsed.formQuestions() != null) {
      for (Map<String, Object> question : parsed.formQuestions()) {
        Object maxChars = question.get("maxChars");
        questions.add(
            new ItemInput(
                null,
                String.valueOf(question.get("question")),
                maxChars instanceof Number number ? number.intValue() : null));
      }
    }
    return questions;
  }

  /** #58 — 상한·형식을 검사하고 order 순으로 정렬한다. 저장 시 1..n 으로 다시 번호를 매긴다. */
  private List<ItemInput> normalizeItems(List<ItemInput> items) {
    if (items.size() > MAX_ITEMS) {
      throw new BusinessException(
          ErrorCode.INVALID_INPUT, "문항은 최대 " + MAX_ITEMS + "개까지 보낼 수 있습니다", "items");
    }
    List<ItemInput> normalized = new ArrayList<>();
    for (int i = 0; i < items.size(); i++) {
      ItemInput item = items.get(i);
      String field = "items[" + i + "]";
      if (item == null || item.question() == null || item.question().isBlank()) {
        throw new BusinessException(
            ErrorCode.INVALID_INPUT, "문항의 question 은 비울 수 없습니다", field + ".question");
      }
      String question = item.question().strip();
      if (question.length() > MAX_QUESTION_CHARS) {
        throw new BusinessException(
            ErrorCode.INVALID_INPUT,
            "문항은 " + MAX_QUESTION_CHARS + "자 이하여야 합니다",
            field + ".question");
      }
      Integer maxChars = item.maxChars();
      if (maxChars != null && (maxChars < 1 || maxChars > MAX_MAX_CHARS)) {
        throw new BusinessException(
            ErrorCode.INVALID_INPUT,
            "maxChars 는 1~" + MAX_MAX_CHARS + " 또는 null 이어야 합니다",
            field + ".maxChars");
      }
      normalized.add(
          new ItemInput(item.order() == null ? i + 1 : item.order(), question, maxChars));
    }
    normalized.sort(java.util.Comparator.comparingInt(ItemInput::order));
    return normalized;
  }

  @Transactional(readOnly = true)
  public ApplicationWithItems get(Long userId, Long applicationId) {
    Application application = owned(userId, applicationId);
    return new ApplicationWithItems(
        application, itemRepository.findAllByApplicationIdOrderByItemOrder(application.id()));
  }

  @Transactional
  public ApplicationItem addItem(
      Long userId, Long applicationId, String question, Integer maxChars) {
    if (question == null || question.isBlank()) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "question 은 비울 수 없습니다", "question");
    }
    Application application = owned(userId, applicationId);
    int nextOrder = itemRepository.countByApplicationId(application.id()) + 1;
    return itemRepository.save(
        new ApplicationItem(application.id(), nextOrder, question.strip(), maxChars));
  }

  @Transactional(readOnly = true)
  public ApplicationItem getItem(Long userId, Long applicationId, Long itemId) {
    return ownedItem(userId, applicationId, itemId);
  }

  @Transactional
  public ApplicationItem editItem(Long userId, Long applicationId, Long itemId, String answer) {
    // 글자 수 초과 저장을 서버가 막지 않는다 — 경고는 클라이언트 몫 (F4-3)
    ApplicationItem item = ownedItem(userId, applicationId, itemId);
    item.edit(answer);
    reopenIfSaved(owned(userId, applicationId));
    return item;
  }

  /** 재생성 준비 (#38) — 항목 loading, 지원서 generating. 저장본이면 공고당 하나 규칙(#57)을 먼저 확인한다. */
  @Transactional
  public ApplicationItem markRegenerating(Long userId, Long applicationId, Long itemId) {
    Application application = owned(userId, applicationId);
    ApplicationItem item = ownedItem(userId, applicationId, itemId);
    if ("saved".equals(application.status())
        && findInProgress(application.userId(), application.postingId())
            .filter(other -> !other.id().equals(application.id()))
            .isPresent()) {
      throw new BusinessException(
          ErrorCode.INVALID_INPUT, "같은 공고에 작성 중인 지원서가 있어 이 저장본은 재생성할 수 없습니다", "id");
    }
    application.restartGeneration();
    item.startRegenerate();
    return item;
  }

  /**
   * 저장본을 다시 고치면 ready 로 돌아간다 (FE canon 「지원서 규칙」). 같은 공고에 이미 다른 진행 중 지원서가 있으면 공고당 하나 규칙(#57)을 지키려
   * saved 로 둔다.
   */
  private void reopenIfSaved(Application application) {
    if (!"saved".equals(application.status())) {
      return;
    }
    boolean otherInProgress =
        findInProgress(application.userId(), application.postingId())
            .filter(other -> !other.id().equals(application.id()))
            .isPresent();
    if (!otherInProgress) {
      application.reopen();
    }
  }

  /** 재생성 직전 현재 답변 보관 — 최대 3버전, 초과분은 오래된 것부터 삭제 (#38). */
  @Transactional
  public void archiveVersion(Long itemId, String answer, String tone) {
    if (answer == null || answer.isBlank()) {
      return;
    }
    List<ApplicationItemVersion> versions =
        versionRepository.findAllByApplicationItemIdOrderByIdAsc(itemId);
    while (versions.size() >= MAX_VERSIONS) {
      versionRepository.delete(versions.remove(0));
    }
    versionRepository.save(new ApplicationItemVersion(itemId, answer, tone));
  }

  @Transactional(readOnly = true)
  public List<ApplicationItemVersion> versions(Long userId, Long applicationId, Long itemId) {
    ownedItem(userId, applicationId, itemId);
    List<ApplicationItemVersion> versions =
        versionRepository.findAllByApplicationItemIdOrderByIdAsc(itemId);
    List<ApplicationItemVersion> reversed = new ArrayList<>(versions);
    java.util.Collections.reverse(reversed);
    return reversed;
  }

  @Transactional
  public ApplicationItem revert(Long userId, Long applicationId, Long itemId, Long versionId) {
    ApplicationItem item = ownedItem(userId, applicationId, itemId);
    ApplicationItemVersion version =
        versionRepository
            .findById(versionId)
            .filter(v -> v.applicationItemId().equals(itemId))
            .orElseThrow(
                () -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "버전을 찾을 수 없습니다"));
    archiveVersion(itemId, item.answer(), null); // 현재 본문도 보관해 왕복 가능하게
    item.edit(version.answer());
    reopenIfSaved(owned(userId, applicationId));
    return item;
  }

  /** 최종 저장 (#39) — saved 전이 + 적합도 스냅샷, 지원 이력은 이 행이 그대로 담당한다. 멱등. */
  @Transactional
  public Application save(Long userId, Long applicationId) {
    Application application = owned(userId, applicationId);
    Integer snapshot =
        scoreRepository
            .findByUserIdAndPostingId(userId, application.postingId())
            .map(score -> score.score())
            .orElse(null);
    application.save(snapshot, clock.instant());
    return application;
  }

  @Transactional
  public Application updateResult(Long userId, Long applicationId, String result) {
    if (result == null || !Application.RESULTS.contains(result)) {
      throw new BusinessException(
          ErrorCode.INVALID_INPUT, "result 는 pending/pass/fail/none 중 하나여야 합니다", "result");
    }
    Application application = owned(userId, applicationId);
    application.updateResult(result);
    return application;
  }

  public record HistoryPage(List<ApplicationWithItems> applications, String nextCursor) {}

  /** 지원 이력 (#40) — id 내림차순 커서 페이징. cursor 는 불투명 문자열(마지막 id). */
  @Transactional(readOnly = true)
  public HistoryPage list(Long userId, String status, String cursor, int limit) {
    long before;
    try {
      before = cursor == null || cursor.isBlank() ? Long.MAX_VALUE : Long.parseLong(cursor);
    } catch (NumberFormatException e) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "cursor 가 올바르지 않습니다", "cursor");
    }
    org.springframework.data.domain.Pageable page =
        org.springframework.data.domain.PageRequest.of(0, limit + 1);
    List<Application> fetched =
        status == null
            ? applicationRepository.findByUserIdAndDeletedAtIsNullAndIdLessThanOrderByIdDesc(
                userId, before, page)
            : applicationRepository
                .findByUserIdAndStatusAndDeletedAtIsNullAndIdLessThanOrderByIdDesc(
                    userId, status, before, page);
    boolean hasMore = fetched.size() > limit;
    List<Application> pageItems = hasMore ? fetched.subList(0, limit) : fetched;
    String next = hasMore ? String.valueOf(pageItems.get(pageItems.size() - 1).id()) : null;
    return new HistoryPage(
        pageItems.stream()
            .map(
                app ->
                    new ApplicationWithItems(
                        app, itemRepository.findAllByApplicationIdOrderByItemOrder(app.id())))
            .toList(),
        next);
  }

  @Transactional
  public void delete(Long userId, Long applicationId) {
    // 이력 삭제 = 연결된 지원서(항목 포함) 삭제 (F4-4). 행 자체가 지원서라 hard delete 로 동반 제거한다.
    Application application = owned(userId, applicationId);
    applicationRepository.delete(application); // items·versions 는 DB cascade
  }

  public Posting postingOf(Application application) {
    return postingRepository.findById(application.postingId()).orElse(null);
  }

  private Application owned(Long userId, Long applicationId) {
    return applicationRepository
        .findByIdAndUserIdAndDeletedAtIsNull(applicationId, userId)
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "지원서를 찾을 수 없습니다"));
  }

  private ApplicationItem ownedItem(Long userId, Long applicationId, Long itemId) {
    owned(userId, applicationId);
    return itemRepository
        .findByIdAndApplicationId(itemId, applicationId)
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "항목을 찾을 수 없습니다"));
  }

  private Posting ownedPosting(Long userId, Long postingId) {
    Posting posting =
        postingRepository
            .findById(postingId)
            .orElseThrow(() -> new BusinessException(ErrorCode.POSTING_NOT_FOUND));
    boolean mine =
        boardRepository
            .findById(posting.boardId())
            .map(board -> board.userId().equals(userId))
            .orElse(false);
    if (!mine) {
      throw new BusinessException(ErrorCode.POSTING_NOT_FOUND);
    }
    return posting;
  }
}
