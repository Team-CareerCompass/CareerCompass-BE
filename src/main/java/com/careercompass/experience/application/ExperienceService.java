package com.careercompass.experience.application;

import com.careercompass.common.api.ErrorCode;
import com.careercompass.common.events.ProfileChanged;
import com.careercompass.common.exception.BusinessException;
import com.careercompass.common.paging.Cursor;
import com.careercompass.common.paging.CursorCodec;
import com.careercompass.common.paging.CursorPage;
import com.careercompass.experience.domain.ExperienceCard;
import com.careercompass.experience.domain.ExperienceCardRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 경험 카드 (#18). 유형 5종의 data 필수 필드(명세서 F1-3 표):
 *
 * <ul>
 *   <li>project — (기간: startDate 필수)
 *   <li>award — contestName(공모전명)·rank(수상 등급, v0.2 이름 prize 도 받음)
 *   <li>intern — company(회사명)·role(직무), startDate 필수
 *   <li>activity — organization(기관명). 활동명은 카드 제목
 *   <li>cert — 필수 키 없음. 자격증명은 카드 제목(issuer·acquiredYearMonth 는 선택)
 * </ul>
 *
 * <p>키 이름은 v0.3 에서 FE(core/data ExperienceMapper)가 실제로 보내는 이름에 맞췄다 — 전에는 award 의 prize·activity 의
 * activityName·cert 의 certName 을 요구해 앱에서 세 유형 카드 저장이 400 으로 실패했다.
 */
@Service
public class ExperienceService {

  public static final String SORT = "created_desc";
  private static final int MAX_CARDS = 30;
  private static final Set<String> TYPES = Set.of("project", "award", "intern", "activity", "cert");

  /** 유형별 필수 키 — 바깥 목록의 각 원소는 「이 중 하나는 있어야 한다」. 에러 field 는 첫 이름. */
  private static final Map<String, List<List<String>>> REQUIRED_DATA_KEYS =
      Map.of(
          "project", List.of(),
          "award", List.of(List.of("contestName"), List.of("rank", "prize")),
          "intern", List.of(List.of("company"), List.of("role")),
          "activity", List.of(List.of("organization")),
          "cert", List.of());

  private static final Set<String> START_DATE_REQUIRED = Set.of("project", "intern");

  private final ExperienceCardRepository repository;
  private final ApplicationEventPublisher eventPublisher;

  public ExperienceService(
      ExperienceCardRepository repository, ApplicationEventPublisher eventPublisher) {
    this.repository = repository;
    this.eventPublisher = eventPublisher;
  }

  @Transactional(readOnly = true)
  public CursorPage<ExperienceCard> list(Long userId, String type, String cursor, int limit) {
    if (type != null && !TYPES.contains(type)) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "알 수 없는 경험 유형입니다: " + type, "type");
    }
    long beforeId = cursor == null ? Long.MAX_VALUE : CursorCodec.decode(cursor, SORT).id();
    PageRequest page = PageRequest.of(0, limit + 1);
    List<ExperienceCard> fetched =
        type == null
            ? repository.findByUserIdAndDeletedAtIsNullAndIdLessThanOrderByIdDesc(
                userId, beforeId, page)
            : repository.findByUserIdAndTypeAndDeletedAtIsNullAndIdLessThanOrderByIdDesc(
                userId, type, beforeId, page);
    return CursorPage.of(fetched, limit, card -> new Cursor(SORT, List.of(), card.id()));
  }

  public record UpsertCard(
      String type,
      String title,
      LocalDate startDate,
      LocalDate endDate,
      Map<String, Object> data) {}

  @Transactional
  public ExperienceCard create(Long userId, UpsertCard request) {
    if (request.type() == null || !TYPES.contains(request.type())) {
      throw new BusinessException(
          ErrorCode.INVALID_INPUT, "type 은 project/award/intern/activity/cert 중 하나여야 합니다", "type");
    }
    if (repository.countByUserIdAndDeletedAtIsNull(userId) >= MAX_CARDS) {
      throw new BusinessException(
          ErrorCode.LIMIT_EXCEEDED, "경험 카드는 최대 " + MAX_CARDS + "개까지 등록할 수 있습니다", null);
    }
    validate(request.type(), request.title(), request.startDate(), request.data(), true);
    ExperienceCard card =
        repository.save(
            new ExperienceCard(
                userId,
                request.type(),
                request.title(),
                request.startDate(),
                request.endDate(),
                request.data()));
    eventPublisher.publishEvent(new ProfileChanged(userId)); // 우대 조건 축 재산출 (#32)
    return card;
  }

  @Transactional
  public ExperienceCard patch(Long userId, Long cardId, UpsertCard request) {
    ExperienceCard card = owned(userId, cardId);
    if (request.type() != null && !request.type().equals(card.type())) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "카드 유형은 변경할 수 없습니다", "type");
    }
    // 수정 후의 최종 상태 기준으로 필수 필드를 재검증한다
    String title = request.title() != null ? request.title() : card.title();
    LocalDate startDate = request.startDate() != null ? request.startDate() : card.startDate();
    Map<String, Object> data = request.data() != null ? request.data() : card.data();
    validate(card.type(), title, startDate, data, true);
    card.update(request.title(), request.startDate(), request.endDate(), request.data());
    eventPublisher.publishEvent(new ProfileChanged(userId));
    return card;
  }

  @Transactional
  public void delete(Long userId, Long cardId) {
    owned(userId, cardId).softDelete();
    eventPublisher.publishEvent(new ProfileChanged(userId));
  }

  private ExperienceCard owned(Long userId, Long cardId) {
    // 남의 카드는 존재 자체를 감춘다 — 404 (#14 방침)
    return repository
        .findByIdAndUserIdAndDeletedAtIsNull(cardId, userId)
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "경험 카드를 찾을 수 없습니다"));
  }

  private void validate(
      String type, String title, LocalDate startDate, Map<String, Object> data, boolean full) {
    if (title == null || title.isBlank()) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "제목은 비울 수 없습니다", "title");
    }
    if (title.length() > 100) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "제목은 100자 이하여야 합니다", "title");
    }
    if (START_DATE_REQUIRED.contains(type) && startDate == null) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "기간(시작일)은 필수입니다", "startDate");
    }
    for (List<String> anyOf : REQUIRED_DATA_KEYS.get(type)) {
      boolean present =
          anyOf.stream()
              .map(key -> data == null ? null : data.get(key))
              .anyMatch(value -> value != null && !(value instanceof String s && s.isBlank()));
      if (!present) {
        String key = anyOf.get(0);
        throw new BusinessException(
            ErrorCode.INVALID_INPUT, "필수 필드가 비어 있습니다: " + key, "data." + key);
      }
    }
  }
}
