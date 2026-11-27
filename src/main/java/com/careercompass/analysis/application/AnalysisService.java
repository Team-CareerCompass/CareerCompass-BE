package com.careercompass.analysis.application;

import com.careercompass.analysis.domain.SuitabilityScore;
import com.careercompass.analysis.domain.SuitabilityScoreRepository;
import com.careercompass.analysis.gateway.LlmGateway;
import com.careercompass.common.api.ErrorCode;
import com.careercompass.common.exception.BusinessException;
import com.careercompass.experience.domain.ExperienceCard;
import com.careercompass.experience.domain.ExperienceCardRepository;
import com.careercompass.posting.domain.Posting;
import com.careercompass.posting.domain.PostingParsed;
import com.careercompass.posting.domain.PostingParsedRepository;
import com.careercompass.posting.domain.PostingRepository;
import com.careercompass.user.domain.UserJobInterestRepository;
import com.careercompass.user.domain.UserProfile;
import com.careercompass.user.domain.UserProfileRepository;
import com.careercompass.user.domain.UserTag;
import com.careercompass.user.domain.UserTagRepository;
import com.careercompass.user.meta.JobCategoryRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 공고 파싱(#31)과 적합도 산출·코멘트(#32, #33). LLM 은 게이트웨이(#35)만 거친다. */
@Service
public class AnalysisService {

  private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);

  private final PostingRepository postingRepository;
  private final PostingParsedRepository parsedRepository;
  private final SuitabilityScoreRepository scoreRepository;
  private final UserProfileRepository profileRepository;
  private final UserJobInterestRepository interestRepository;
  private final UserTagRepository tagRepository;
  private final JobCategoryRepository jobCategoryRepository;
  private final ExperienceCardRepository experienceCardRepository;
  private final LlmGateway llmGateway;
  private final SuitabilityCalculator calculator;
  private final Clock clock;

  public AnalysisService(
      PostingRepository postingRepository,
      PostingParsedRepository parsedRepository,
      SuitabilityScoreRepository scoreRepository,
      UserProfileRepository profileRepository,
      UserJobInterestRepository interestRepository,
      UserTagRepository tagRepository,
      JobCategoryRepository jobCategoryRepository,
      ExperienceCardRepository experienceCardRepository,
      LlmGateway llmGateway,
      SuitabilityCalculator calculator,
      Clock clock) {
    this.postingRepository = postingRepository;
    this.parsedRepository = parsedRepository;
    this.scoreRepository = scoreRepository;
    this.profileRepository = profileRepository;
    this.interestRepository = interestRepository;
    this.tagRepository = tagRepository;
    this.jobCategoryRepository = jobCategoryRepository;
    this.experienceCardRepository = experienceCardRepository;
    this.llmGateway = llmGateway;
    this.calculator = calculator;
    this.clock = clock;
  }

  /**
   * 공고 1건 구조화 파싱 (#31). 파싱 실패는 failed 확정, LLM 장애는 pending 유지(다음 스윕에서 재시도).
   *
   * @return true 면 done 으로 전이
   */
  @Transactional
  public boolean parsePosting(Long postingId) {
    Posting posting = postingRepository.findById(postingId).orElse(null);
    PostingParsed parsed = parsedRepository.findById(postingId).orElse(null);
    if (posting == null || parsed == null || "done".equals(parsed.parseStatus())) {
      return false;
    }
    try {
      LlmGateway.ParsedPosting result =
          llmGateway.parsePosting(posting.title(), posting.rawContent());

      Map<String, Object> qualifications = new LinkedHashMap<>();
      qualifications.put("year", result.qualificationYear());
      qualifications.put("gpa", result.qualificationGpa());
      qualifications.put("major", result.qualificationMajor());
      List<Map<String, Object>> formQuestions =
          result.formQuestions().stream()
              .map(
                  q -> {
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("order", q.order());
                    m.put("question", q.question());
                    m.put("maxChars", q.maxChars());
                    return m;
                  })
              .toList();
      parsed.parsed(result.keywords(), qualifications, result.preferences(), formQuestions);
      posting.applyParsed(result.type(), result.dueDate());
      return true;
    } catch (LlmGateway.ParsingFailedException e) {
      if (e.notAPosting()) {
        parsed.parseExcluded(); // 학사공지 등 — 목록·적합도 대상에서 뺀다 (#65)
      } else {
        parsed.parseFailed();
      }
      return false;
    } catch (BusinessException e) {
      if (e.code() == ErrorCode.LLM_UNAVAILABLE) {
        log.warn("LLM 사용 불가 — posting {} 파싱을 다음 스윕으로 미룬다", postingId);
        return false; // pending 유지
      }
      throw e;
    }
  }

  /** 사용자×공고 적합도 산출·저장 (#32). 코멘트 실패는 점수를 막지 않는다 (#33). */
  @Transactional
  public SuitabilityScore computeScore(Long userId, Long postingId) {
    Posting posting =
        postingRepository
            .findById(postingId)
            .orElseThrow(() -> new BusinessException(ErrorCode.POSTING_NOT_FOUND));
    PostingParsed parsed =
        parsedRepository
            .findById(postingId)
            .filter(p -> "done".equals(p.parseStatus()))
            .orElseThrow(
                () -> new BusinessException(ErrorCode.PARSING_FAILED, "구조화 파싱이 완료되지 않았습니다"));

    UserProfile profile = profileRepository.findById(userId).orElse(null);
    List<String> interestTexts = interestTexts(userId);
    List<ExperienceCard> cards =
        experienceCardRepository.findByUserIdAndDeletedAtIsNullAndIdLessThanOrderByIdDesc(
            userId, Long.MAX_VALUE, PageRequest.of(0, 30));
    if (interestTexts.isEmpty() && cards.isEmpty()) {
      throw new BusinessException(ErrorCode.PROFILE_INCOMPLETE); // #34
    }

    Map<String, Object> qualifications =
        parsed.qualifications() == null ? Map.of() : parsed.qualifications();
    List<String> keywords = parsed.keywords() == null ? List.of() : parsed.keywords();
    // 분야 축만 AI 임베딩 점수로 (AI 계약 §5). 실패·미연결이면 null → 계산기가 글자 비교로 낸다
    Integer fieldScore =
        llmGateway
            .fieldScores(
                interestTexts,
                List.of(new LlmGateway.FieldPosting(postingId, posting.title(), keywords)))
            .get(postingId);
    SuitabilityCalculator.Result result =
        calculator.calculate(
            new SuitabilityCalculator.Input(
                keywords,
                asText(qualifications.get("year")),
                asText(qualifications.get("gpa")),
                asText(qualifications.get("major")),
                parsed.preferences() == null ? List.of() : parsed.preferences(),
                posting.rawContent(),
                interestTexts,
                profile == null ? null : profile.department(),
                profile == null ? null : profile.gpa(),
                cards.stream().map(this::cardText).toList(),
                fieldScore));

    List<Map<String, Object>> breakdown =
        result.breakdown().stream()
            .map(
                axis -> {
                  Map<String, Object> m = new LinkedHashMap<String, Object>();
                  m.put("axis", axis.axis());
                  m.put("score", axis.score());
                  m.put("weight", axis.weight());
                  return m;
                })
            .toList();

    SuitabilityScore score =
        scoreRepository
            .findByUserIdAndPostingId(userId, postingId)
            .orElseGet(
                () ->
                    scoreRepository.save(
                        new SuitabilityScore(
                            userId,
                            postingId,
                            result.total(),
                            result.label(),
                            breakdown,
                            clock.instant())));
    score.updateScore(result.total(), result.label(), breakdown, clock.instant());

    try {
      LlmGateway.Comments comments =
          llmGateway.comments(
              new LlmGateway.CommentContext(
                  result.matchedKeywords(),
                  result.missingQualifications(),
                  result.matchedPreferences(),
                  cards.isEmpty() ? null : cards.get(0).title()));
      score.updateComments(comments.strength(), comments.weakness());
    } catch (BusinessException e) {
      log.warn("코멘트 생성 실패(점수는 저장됨): user={} posting={}", userId, postingId);
    }
    return score;
  }

  /** 프로필 변경 시 무효화 — 스윕이 다시 채운다 (#32 재산출 정책: 사전 계산 + 변경 시 무효화). */
  @Transactional
  public void invalidateScores(Long userId) {
    scoreRepository.deleteAllByUserId(userId);
  }

  private List<String> interestTexts(Long userId) {
    List<String> texts = new ArrayList<>();
    interestRepository
        .findAllByUserIdOrderByPriority(userId)
        .forEach(
            interest -> {
              texts.add(interest.code());
              jobCategoryRepository
                  .findByCode(interest.code())
                  .ifPresent(category -> texts.add(category.label()));
            });
    tagRepository.findAllByUserIdOrderById(userId).stream().map(UserTag::tag).forEach(texts::add);
    return texts.stream().filter(Objects::nonNull).toList();
  }

  private String cardText(ExperienceCard card) {
    StringBuilder sb = new StringBuilder(card.title());
    if (card.data() != null) {
      card.data().values().forEach(value -> sb.append(' ').append(value));
    }
    return sb.toString();
  }

  private String asText(Object value) {
    return value == null ? null : value.toString();
  }
}
