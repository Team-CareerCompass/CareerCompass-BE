package com.careercompass.user.application;

import com.careercompass.common.api.ErrorCode;
import com.careercompass.common.events.ProfileChanged;
import com.careercompass.common.exception.BusinessException;
import com.careercompass.experience.domain.ExperienceCardRepository;
import com.careercompass.pastapplication.domain.PastApplicationRepository;
import com.careercompass.user.domain.User;
import com.careercompass.user.domain.UserConsent;
import com.careercompass.user.domain.UserConsentRepository;
import com.careercompass.user.domain.UserJobInterest;
import com.careercompass.user.domain.UserJobInterestRepository;
import com.careercompass.user.domain.UserProfile;
import com.careercompass.user.domain.UserProfileRepository;
import com.careercompass.user.domain.UserRepository;
import com.careercompass.user.domain.UserTag;
import com.careercompass.user.domain.UserTagRepository;
import com.careercompass.user.meta.JobCategoryRepository;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 프로필 규칙 (#15, #16 에서 확정 — API_SPEC v0.2):
 *
 * <ul>
 *   <li>onboardingDone = Step1(학교·학과 입력) 그리고 Step2(희망 직무 ≥1). Step3 경험·Step4 지원서는 선택. 한 번 true 가 되면
 *       되돌리지 않는다.
 *   <li>completion(%) = 이름10 + 학교10 + 학과10 + 학점10 + 졸업연도10 + 희망직무15 + 태그10 + 경험카드15 + 과거지원서10.
 *   <li>태그 정규화: 앞뒤 공백 제거·연속 공백 축약, 정규화 후 중복은 조용히 제거.
 * </ul>
 */
@Service
public class UserProfileService {

  private final UserRepository userRepository;
  private final UserProfileRepository profileRepository;
  private final UserJobInterestRepository interestRepository;
  private final UserTagRepository tagRepository;
  private final JobCategoryRepository jobCategoryRepository;
  private final ExperienceCardRepository experienceCardRepository;
  private final PastApplicationRepository pastApplicationRepository;
  private final ApplicationEventPublisher eventPublisher;
  private final UserConsentRepository consentRepository;

  public UserProfileService(
      UserRepository userRepository,
      UserProfileRepository profileRepository,
      UserJobInterestRepository interestRepository,
      UserTagRepository tagRepository,
      JobCategoryRepository jobCategoryRepository,
      ExperienceCardRepository experienceCardRepository,
      PastApplicationRepository pastApplicationRepository,
      ApplicationEventPublisher eventPublisher,
      UserConsentRepository consentRepository) {
    this.userRepository = userRepository;
    this.profileRepository = profileRepository;
    this.interestRepository = interestRepository;
    this.tagRepository = tagRepository;
    this.jobCategoryRepository = jobCategoryRepository;
    this.experienceCardRepository = experienceCardRepository;
    this.pastApplicationRepository = pastApplicationRepository;
    this.eventPublisher = eventPublisher;
    this.consentRepository = consentRepository;
  }

  public record JobInterestItem(String code, int priority) {}

  public record Me(
      Long id,
      String name,
      String school,
      String department,
      BigDecimal gpa,
      Integer gradYear,
      List<JobInterestItem> jobInterests,
      List<String> tags,
      boolean onboardingDone,
      int completion) {}

  @Transactional(readOnly = true)
  public Me getMe(Long userId) {
    return assemble(userId);
  }

  public record PatchMe(
      String name, String school, String department, BigDecimal gpa, Integer gradYear) {}

  @Transactional
  public Me patchMe(Long userId, PatchMe patch) {
    User user = user(userId);
    UserProfile profile = profile(userId);
    if (patch.name() != null) {
      user.rename(patch.name());
    }
    profile.update(patch.school(), patch.department(), patch.gpa(), patch.gradYear());
    refreshOnboarding(profile);
    eventPublisher.publishEvent(new ProfileChanged(userId)); // 적합도 재산출 트리거 (#32)
    return assemble(userId);
  }

  @Transactional
  public Me replaceJobInterests(Long userId, List<JobInterestItem> interests) {
    if (interests == null || interests.isEmpty() || interests.size() > 3) {
      throw new BusinessException(
          ErrorCode.LIMIT_EXCEEDED, "희망 직무는 1개 이상 3개 이하여야 합니다", "interests");
    }
    Set<String> codes = new LinkedHashSet<>();
    Set<Integer> priorities = new LinkedHashSet<>();
    for (JobInterestItem item : interests) {
      if (jobCategoryRepository.findByCode(item.code()).isEmpty()) {
        throw new BusinessException(
            ErrorCode.INVALID_INPUT, "알 수 없는 직무 코드입니다: " + item.code(), "interests");
      }
      if (!codes.add(item.code())) {
        throw new BusinessException(
            ErrorCode.INVALID_INPUT, "직무 코드가 중복되었습니다: " + item.code(), "interests");
      }
      if (item.priority() < 1 || item.priority() > 3 || !priorities.add(item.priority())) {
        throw new BusinessException(
            ErrorCode.INVALID_INPUT, "priority 는 1~3 범위의 중복 없는 값이어야 합니다", "interests");
      }
    }
    interestRepository.deleteByUserId(userId);
    interestRepository.saveAll(
        interests.stream().map(i -> new UserJobInterest(userId, i.code(), i.priority())).toList());
    refreshOnboarding(profile(userId));
    eventPublisher.publishEvent(new ProfileChanged(userId));
    return assemble(userId);
  }

  @Transactional
  public Me replaceTags(Long userId, List<String> tags) {
    if (tags == null) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "tags 가 필요합니다", "tags");
    }
    LinkedHashSet<String> normalized = new LinkedHashSet<>();
    for (String raw : tags) {
      if (raw == null || raw.isBlank()) {
        throw new BusinessException(ErrorCode.INVALID_INPUT, "빈 태그는 넣을 수 없습니다", "tags");
      }
      if (raw.trim().length() > 50) {
        throw new BusinessException(ErrorCode.INVALID_INPUT, "태그는 50자 이하여야 합니다", "tags");
      }
      normalized.add(raw.trim().replaceAll("\\s+", " "));
    }
    if (normalized.isEmpty() || normalized.size() > 5) {
      throw new BusinessException(ErrorCode.LIMIT_EXCEEDED, "관심 태그는 1개 이상 5개 이하여야 합니다", "tags");
    }
    tagRepository.deleteByUserId(userId);
    tagRepository.saveAll(normalized.stream().map(t -> new UserTag(userId, t)).toList());
    eventPublisher.publishEvent(new ProfileChanged(userId));
    return assemble(userId);
  }

  @Transactional
  public boolean updateConsent(Long userId, boolean modelImprovement) {
    user(userId); // 존재 확인
    UserConsent consent =
        consentRepository.findById(userId).orElseGet(() -> new UserConsent(userId));
    consent.setModelImprovement(modelImprovement);
    consentRepository.save(consent);
    return consent.modelImprovement();
  }

  private void refreshOnboarding(UserProfile profile) {
    if (profile.onboardingDone()) {
      return;
    }
    boolean step1 = profile.school() != null && profile.department() != null;
    boolean step2 = !interestRepository.findAllByUserIdOrderByPriority(profile.userId()).isEmpty();
    if (step1 && step2) {
      profile.completeOnboarding();
    }
  }

  private Me assemble(Long userId) {
    User user = user(userId);
    UserProfile profile = profile(userId);
    List<JobInterestItem> interests =
        interestRepository.findAllByUserIdOrderByPriority(userId).stream()
            .map(i -> new JobInterestItem(i.code(), i.priority()))
            .toList();
    List<String> tags =
        tagRepository.findAllByUserIdOrderById(userId).stream().map(UserTag::tag).toList();

    int completion = 0;
    completion += user.name() != null ? 10 : 0;
    completion += profile.school() != null ? 10 : 0;
    completion += profile.department() != null ? 10 : 0;
    completion += profile.gpa() != null ? 10 : 0;
    completion += profile.gradYear() != null ? 10 : 0;
    completion += !interests.isEmpty() ? 15 : 0;
    completion += !tags.isEmpty() ? 10 : 0;
    completion += experienceCardRepository.countByUserIdAndDeletedAtIsNull(userId) > 0 ? 15 : 0;
    completion += pastApplicationRepository.countByUserId(userId) > 0 ? 10 : 0;

    return new Me(
        user.id(),
        user.name(),
        profile.school(),
        profile.department(),
        profile.gpa(),
        profile.gradYear(),
        interests,
        tags,
        profile.onboardingDone(),
        completion);
  }

  private User user(Long userId) {
    return userRepository
        .findById(userId)
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "사용자를 찾을 수 없습니다"));
  }

  private UserProfile profile(Long userId) {
    return profileRepository
        .findById(userId)
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "프로필을 찾을 수 없습니다"));
  }
}
