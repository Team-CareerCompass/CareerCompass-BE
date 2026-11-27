package com.careercompass.analysis.gateway;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 결정적 온프레미스 구현 — AI 서버 없이 전체 파이프라인이 돈다. PII·공고 본문을 외부로 보내지 않는다. 품질은 LLM 구현(CareerCompass-AI)이 대체하는
 * 것을 전제로 한 기준선이다. 빈 등록은 {@link LlmGatewayConfig} 가 한다.
 */
public class HeuristicLlmGateway implements LlmGateway {

  private static final Pattern WORD = Pattern.compile("[A-Za-z가-힣][A-Za-z0-9가-힣+#.]{1,19}");
  private static final Set<String> STOPWORDS =
      Set.of(
          "안내", "공고", "모집", "지원", "신청", "제출", "관련", "대상", "기간", "문의", "첨부", "이상", "이하", "및", "또는",
          "경우", "해당", "가능", "위한", "통해", "있는", "있습니다", "합니다", "바랍니다", "주시기", "학년", "학점", "우대", "마감",
          "접수", "채용", "장학금", "공모전", "대외활동", "내용", "예정", "진행", "운영");
  private static final Pattern FULL_DATE =
      Pattern.compile("(20\\d{2})[.\\-/년]\\s?(\\d{1,2})[.\\-/월]\\s?(\\d{1,2})");
  private static final Pattern SHORT_DATE =
      Pattern.compile("[~까지]\\s?(\\d{1,2})\\s?[/월]\\s?(\\d{1,2})");
  private static final Pattern QUESTION =
      Pattern.compile("(?m)^\\s*(\\d)[.)]\\s*(.{5,120}?)\\s*(?:\\((\\d{2,5})자[^)]*\\))?\\s*$");

  @Override
  public ParsedPosting parsePosting(String title, String rawContent) {
    String text = (title == null ? "" : title) + "\n" + (rawContent == null ? "" : rawContent);
    if (text.isBlank()) {
      throw new ParsingFailedException("본문이 비어 있습니다");
    }

    List<String> keywords = extractKeywords(title, rawContent);
    if (keywords.size() < 3) {
      throw new ParsingFailedException("핵심 키워드가 3개 미만입니다"); // #31 실패 규칙
    }

    return new ParsedPosting(
        reclassify(text),
        keywords,
        firstMatch(text, Pattern.compile("(\\d)\\s?학년\\s?(?:이상|재학)")),
        firstMatch(text, Pattern.compile("(?:학점|평점)\\s?(\\d\\.\\d+)\\s?(?:이상)?")),
        firstMatch(text, Pattern.compile("([가-힣]{2,10}(?:학과|학부|전공|계열))")),
        extractPreferences(rawContent),
        extractDueDate(text),
        extractQuestions(rawContent));
  }

  private List<String> extractKeywords(String title, String rawContent) {
    Map<String, Integer> scores = new LinkedHashMap<>();
    if (title != null) {
      Matcher m = WORD.matcher(title);
      while (m.find()) {
        String word = m.group();
        if (!STOPWORDS.contains(word)) {
          scores.merge(word, 5, Integer::sum); // 제목 가중
        }
      }
    }
    if (rawContent != null) {
      Matcher m = WORD.matcher(rawContent);
      while (m.find()) {
        String word = m.group();
        if (!STOPWORDS.contains(word) && word.length() >= 2) {
          scores.merge(word, 1, Integer::sum);
        }
      }
    }
    return scores.entrySet().stream()
        .filter(e -> e.getValue() >= 2)
        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
        .limit(10)
        .map(Map.Entry::getKey)
        .toList();
  }

  private String reclassify(String text) {
    if (text.contains("장학")) {
      return "scholarship";
    }
    if (text.contains("공모전") || text.contains("경진대회") || text.contains("해커톤")) {
      return "contest";
    }
    if (text.contains("채용") || text.contains("인턴") || text.contains("정규직")) {
      return "recruit";
    }
    if (text.contains("대외활동") || text.contains("서포터즈") || text.contains("봉사")) {
      return "activity";
    }
    return null; // 재분류 실패 → 게시판 등록 유형 유지 (#31)
  }

  private String firstMatch(String text, Pattern pattern) {
    Matcher matcher = pattern.matcher(text);
    return matcher.find() ? matcher.group(0).trim() : null;
  }

  private List<String> extractPreferences(String rawContent) {
    if (rawContent == null) {
      return List.of();
    }
    List<String> preferences = new ArrayList<>();
    for (String line : rawContent.split("[\\n·•\\-]")) {
      String trimmed = line.strip();
      boolean headerLine =
          trimmed.endsWith(":") || trimmed.endsWith("：") || trimmed.matches("우대\\s?사항?[:：]?");
      if (!headerLine
          && trimmed.contains("우대")
          && trimmed.length() >= 5
          && trimmed.length() <= 100) {
        preferences.add(trimmed);
      }
    }
    return preferences.stream().limit(10).toList();
  }

  private LocalDate extractDueDate(String text) {
    if (text.contains("상시")) {
      return null;
    }
    // 「마감」 등의 키워드 근처를 우선, 없으면 전체에서 마지막 완전 날짜
    LocalDate result = null;
    Matcher full = FULL_DATE.matcher(text);
    while (full.find()) {
      try {
        result =
            LocalDate.of(
                Integer.parseInt(full.group(1)),
                Integer.parseInt(full.group(2)),
                Integer.parseInt(full.group(3)));
      } catch (Exception ignored) {
        // 잘못된 날짜는 무시
      }
    }
    if (result != null) {
      return result;
    }
    Matcher shortDate = SHORT_DATE.matcher(text);
    if (shortDate.find()) {
      try {
        return LocalDate.of(
            LocalDate.now().getYear(),
            Integer.parseInt(shortDate.group(1)),
            Integer.parseInt(shortDate.group(2)));
      } catch (Exception ignored) {
        return null;
      }
    }
    return null;
  }

  private List<FormQuestion> extractQuestions(String rawContent) {
    if (rawContent == null) {
      return List.of();
    }
    List<FormQuestion> questions = new ArrayList<>();
    Matcher matcher = QUESTION.matcher(rawContent);
    while (matcher.find() && questions.size() < 10) {
      String body = matcher.group(2).strip();
      if (body.contains("?")
          || body.contains("작성")
          || body.contains("기술")
          || body.contains("서술")
          || body.contains("주세요")) {
        questions.add(
            new FormQuestion(
                Integer.parseInt(matcher.group(1)),
                body,
                matcher.group(3) == null ? null : Integer.parseInt(matcher.group(3))));
      }
    }
    return questions;
  }

  @Override
  public Comments comments(CommentContext context) {
    StringBuilder strength = new StringBuilder();
    if (!context.matchedPreferences().isEmpty() && context.topExperienceTitle() != null) {
      strength
          .append("귀하의 「")
          .append(context.topExperienceTitle())
          .append("」 경험이 이 공고의 우대 조건(")
          .append(context.matchedPreferences().get(0))
          .append(")과 일치합니다.");
    } else if (!context.matchedKeywords().isEmpty()) {
      strength
          .append("관심 분야가 공고 핵심 키워드(")
          .append(String.join(", ", context.matchedKeywords().stream().limit(3).toList()))
          .append(")와 맞닿아 있습니다.");
    } else {
      strength.append("기본 지원 요건을 충족하고 있습니다.");
    }

    String weakness;
    if (!context.missingQualifications().isEmpty()) {
      weakness =
          "이 공고는 "
              + String.join(", ", context.missingQualifications())
              + " 조건을 요구하나 해당 정보가 프로필에 없거나 부족합니다.";
    } else if (context.matchedPreferences().isEmpty()) {
      weakness = "우대 조건과 직접 연결되는 경험 카드가 아직 없습니다. 관련 경험을 추가하면 적합도가 올라갑니다.";
    } else {
      weakness = "뚜렷한 결격 요건은 보이지 않습니다.";
    }
    return new Comments(strength.toString(), weakness);
  }

  @Override
  public String draftAnswer(
      String question,
      Integer maxChars,
      String tone,
      String postingTitle,
      List<String> keywords,
      List<String> experienceSummaries) {
    String opening =
        switch (tone == null ? "formal" : tone) {
          case "casual" -> "저는 " + postingTitle + " 공고를 보고 바로 이거다 싶었습니다. ";
          case "confident" -> "저만큼 이 자리에 준비된 지원자는 많지 않다고 생각합니다. ";
          default -> "저는 " + postingTitle + " 에 지원하고자 합니다. ";
        };
    StringBuilder body = new StringBuilder(opening);
    body.append("질문 「").append(question).append("」 에 대해 말씀드리면, ");
    LinkedHashSet<String> used =
        new LinkedHashSet<>(experienceSummaries.stream().limit(2).toList());
    for (String exp : used) {
      body.append(exp).append(" 경험을 통해 역량을 쌓았습니다. ");
    }
    if (!keywords.isEmpty()) {
      body.append("특히 ")
          .append(String.join(", ", keywords.stream().limit(3).toList()))
          .append(" 분야에서 기여할 수 있습니다. ");
    }
    body.append("입사 후에도 꾸준히 성장하며 팀에 보탬이 되겠습니다.");
    String draft = body.toString();
    if (maxChars != null && draft.length() > maxChars) {
      draft = draft.substring(0, Math.max(0, maxChars - 1)) + "…";
    }
    return draft;
  }
}
