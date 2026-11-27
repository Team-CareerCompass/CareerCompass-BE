package com.careercompass.application.application;

import com.careercompass.experience.domain.ExperienceCard;
import com.careercompass.experience.domain.ExperienceCardRepository;
import com.careercompass.pastapplication.domain.PastApplication;
import com.careercompass.pastapplication.domain.PastApplicationItem;
import com.careercompass.pastapplication.domain.PastApplicationItemRepository;
import com.careercompass.pastapplication.domain.PastApplicationRepository;
import com.careercompass.posting.domain.Posting;
import com.careercompass.posting.domain.PostingParsed;
import com.careercompass.posting.domain.PostingParsedRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * 초안 생성 컨텍스트 (#36) — 경험 카드 참조 우선순위: ① 공고 키워드 유사도 ② 우대 조건 직접 매칭 ③ 최신순. 과거 지원서가 있으면 질문 성격에 맞는 항목을 참조로
 * 덧붙인다(없으면 카드만).
 */
@Component
public class DraftContextBuilder {

  public record Context(String postingTitle, List<String> keywords, List<Long> rankedCardIds) {}

  private final PostingParsedRepository parsedRepository;
  private final ExperienceCardRepository cardRepository;
  private final PastApplicationRepository pastApplicationRepository;
  private final PastApplicationItemRepository pastItemRepository;

  public DraftContextBuilder(
      PostingParsedRepository parsedRepository,
      ExperienceCardRepository cardRepository,
      PastApplicationRepository pastApplicationRepository,
      PastApplicationItemRepository pastItemRepository) {
    this.parsedRepository = parsedRepository;
    this.cardRepository = cardRepository;
    this.pastApplicationRepository = pastApplicationRepository;
    this.pastItemRepository = pastItemRepository;
  }

  public Context build(Long userId, Posting posting) {
    PostingParsed parsed = parsedRepository.findById(posting.id()).orElse(null);
    List<String> keywords =
        parsed == null || parsed.keywords() == null ? List.of() : parsed.keywords();
    List<String> preferences =
        parsed == null || parsed.preferences() == null ? List.of() : parsed.preferences();

    List<ExperienceCard> cards =
        cardRepository.findByUserIdAndDeletedAtIsNullAndIdLessThanOrderByIdDesc(
            userId, Long.MAX_VALUE, PageRequest.of(0, 30));
    record Ranked(ExperienceCard card, int score) {}
    List<Long> ranked =
        cards.stream()
            .map(
                card -> {
                  String text = cardText(card).toLowerCase(Locale.ROOT);
                  int keywordScore =
                      (int)
                          keywords.stream()
                              .filter(k -> text.contains(k.toLowerCase(Locale.ROOT)))
                              .count();
                  int preferenceScore =
                      (int)
                          preferences.stream()
                              .filter(
                                  p -> {
                                    for (String token :
                                        p.toLowerCase(Locale.ROOT).split("[^a-z0-9가-힣+#.]+")) {
                                      if (token.length() >= 2
                                          && !token.equals("우대")
                                          && text.contains(token)) {
                                        return true;
                                      }
                                    }
                                    return false;
                                  })
                              .count();
                  return new Ranked(card, keywordScore * 10 + preferenceScore * 5);
                })
            .sorted(
                Comparator.<Ranked>comparingInt(Ranked::score)
                    .reversed()
                    .thenComparing(r -> r.card().id(), Comparator.reverseOrder()))
            .map(r -> r.card().id())
            .toList();

    return new Context(posting.title(), keywords, ranked);
  }

  /** 항목별 참조 요약 — 강조 카드를 앞으로, 그 뒤 우선순위 카드, 마지막에 과거 자소서 발췌. */
  public List<String> summariesFor(
      Long userId, Context context, String question, List<Long> emphasizeCardIds) {
    LinkedHashSet<Long> orderedIds = new LinkedHashSet<>();
    if (emphasizeCardIds != null) {
      orderedIds.addAll(emphasizeCardIds);
    }
    orderedIds.addAll(context.rankedCardIds());

    List<String> summaries = new ArrayList<>();
    for (Long cardId : orderedIds) {
      if (summaries.size() >= 3) {
        break;
      }
      cardRepository
          .findByIdAndUserIdAndDeletedAtIsNull(cardId, userId)
          .ifPresent(card -> summaries.add(cardText(card)));
    }
    pastExcerpt(userId, question).ifPresent(summaries::add);
    return summaries;
  }

  private java.util.Optional<String> pastExcerpt(Long userId, String question) {
    String category = categoryOf(question);
    for (PastApplication app : pastApplicationRepository.findAllByUserIdOrderByIdDesc(userId)) {
      for (PastApplicationItem item :
          pastItemRepository.findAllByPastApplicationIdOrderByItemOrder(app.id())) {
        if (item.category().equals(category)) {
          String content = item.content();
          return java.util.Optional.of(
              "과거 자소서 발췌: " + content.substring(0, Math.min(120, content.length())));
        }
      }
    }
    return java.util.Optional.empty();
  }

  private String categoryOf(String question) {
    if (question.contains("동기")) {
      return "motivation";
    }
    if (question.contains("포부") || question.contains("입사 후")) {
      return "aspiration";
    }
    if (question.contains("강점") || question.contains("역량")) {
      return "competency";
    }
    if (question.contains("성장")) {
      return "background";
    }
    return "experience";
  }

  private String cardText(ExperienceCard card) {
    StringBuilder sb = new StringBuilder(card.title());
    if (card.data() != null) {
      // FE 가 보내는 키(rank·organization·issuer·techs)와 v0.2 이름(prize·certName)을 함께 읽는다
      Set<String> keys =
          Set.of(
              "summary",
              "role",
              "company",
              "contestName",
              "rank",
              "prize",
              "organization",
              "issuer",
              "techs",
              "certName");
      card.data()
          .forEach(
              (key, value) -> {
                if (keys.contains(key) && value != null) {
                  sb.append(' ').append(value);
                }
              });
    }
    return sb.toString();
  }
}
