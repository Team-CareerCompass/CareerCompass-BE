package com.careercompass.analysis.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 4축 가중 산출 (F3-2, #32) — 순수 함수: 같은 입력이면 같은 점수(결정성). 자격 축은 「미기입 항목은 충족 간주」 규칙을 따른다.
 *
 * <p>축 이름은 스펙 고정: field_similarity / qualification / preference / competition. 표시 문구 매핑은 클라이언트가 한다
 * (#33 결정 — FE 기존 방식 유지).
 */
@Component
public class SuitabilityCalculator {

  public record Input(
      List<String> keywords,
      String qualificationYear,
      String qualificationGpa,
      String qualificationMajor,
      List<String> preferences,
      String rawContent,
      List<String> interestTexts, // 직무 라벨 + 태그
      String department,
      BigDecimal gpa,
      List<String> experienceTexts, // 카드 제목 + data 값 결합 텍스트
      Integer fieldScore) { // AI 분야 유사도(임베딩) 0~100. null 이면 글자 비교로 낸다 (AI 계약 §5)

    /** AI 분야 유사도 없이 — 글자 비교만 쓰는 입력. */
    public Input(
        List<String> keywords,
        String qualificationYear,
        String qualificationGpa,
        String qualificationMajor,
        List<String> preferences,
        String rawContent,
        List<String> interestTexts,
        String department,
        BigDecimal gpa,
        List<String> experienceTexts) {
      this(
          keywords,
          qualificationYear,
          qualificationGpa,
          qualificationMajor,
          preferences,
          rawContent,
          interestTexts,
          department,
          gpa,
          experienceTexts,
          null);
    }
  }

  public record AxisScore(String axis, int score, int weight) {}

  public record Result(
      int total,
      String label,
      List<AxisScore> breakdown,
      List<String> matchedKeywords,
      List<String> missingQualifications,
      List<String> matchedPreferences) {}

  private static final Pattern NUMBER = Pattern.compile("(\\d+(?:\\.\\d+)?)");

  private final AnalysisProperties properties;

  public SuitabilityCalculator(AnalysisProperties properties) {
    this.properties = properties;
  }

  public Result calculate(Input input) {
    List<String> matchedKeywords = new ArrayList<>(); // 코멘트 근거는 AI 점수가 있어도 글자 비교로 만든다
    int keywordField = fieldSimilarity(input, matchedKeywords);
    int field =
        input.fieldScore() == null ? keywordField : Math.max(0, Math.min(100, input.fieldScore()));
    List<String> missing = new ArrayList<>();
    int qualification = qualification(input, missing);
    List<String> matchedPreferences = new ArrayList<>();
    int preference = preference(input, matchedPreferences);
    int competition = competition(input.rawContent());

    Map<String, Integer> axes = new LinkedHashMap<>();
    axes.put("field_similarity", field);
    axes.put("qualification", qualification);
    axes.put("preference", preference);
    axes.put("competition", competition);
    Map<String, Integer> weights =
        Map.of(
            "field_similarity", properties.fieldWeight(),
            "qualification", properties.qualificationWeight(),
            "preference", properties.preferenceWeight(),
            "competition", properties.competitionWeight());

    int weighted = 0;
    List<AxisScore> breakdown = new ArrayList<>();
    for (Map.Entry<String, Integer> axis : axes.entrySet()) {
      int weight = weights.get(axis.getKey());
      breakdown.add(new AxisScore(axis.getKey(), axis.getValue(), weight));
      weighted += axis.getValue() * weight;
    }
    int total = Math.round(weighted / (float) properties.weightSum());

    return new Result(total, label(total), breakdown, matchedKeywords, missing, matchedPreferences);
  }

  public static String label(int score) {
    if (score >= 80) {
      return "very_suitable";
    }
    if (score >= 60) {
      return "suitable";
    }
    if (score >= 40) {
      return "normal";
    }
    return "low";
  }

  /** 공고 키워드 ↔ 관심 분야(직무 라벨·태그) 겹침 비율. */
  private int fieldSimilarity(Input input, List<String> matchedOut) {
    if (input.keywords() == null || input.keywords().isEmpty()) {
      return 0;
    }
    if (input.interestTexts().isEmpty()) {
      return 0; // 관심 분야 미입력 — 잠정 0 (#34 는 별도 사유로 안내)
    }
    List<String> interests =
        input.interestTexts().stream().map(t -> t.toLowerCase(Locale.ROOT)).toList();
    for (String keyword : input.keywords()) {
      String lower = keyword.toLowerCase(Locale.ROOT);
      boolean hit = interests.stream().anyMatch(i -> i.contains(lower) || lower.contains(i));
      if (hit) {
        matchedOut.add(keyword);
      }
    }
    int denominator = Math.min(input.keywords().size(), 5);
    return Math.min(100, Math.round(matchedOut.size() * 100f / denominator));
  }

  /** 학점·학년·전공 — 공고가 요구하지 않거나 사용자 미기입이면 충족 간주 (명세서 F3-2). */
  private int qualification(Input input, List<String> missingOut) {
    int total = 0;
    int satisfied = 0;

    if (input.qualificationGpa() != null) {
      total++;
      BigDecimal required = firstNumber(input.qualificationGpa());
      if (input.gpa() == null || required == null || input.gpa().compareTo(required) >= 0) {
        satisfied++;
      } else {
        missingOut.add("학점 " + input.qualificationGpa());
      }
    }
    if (input.qualificationYear() != null) {
      total++;
      satisfied++; // 학년은 프로필에 직접 항목이 없어 충족 간주 (미기입 규칙)
    }
    if (input.qualificationMajor() != null) {
      total++;
      String requiredMajor = input.qualificationMajor();
      if (input.department() == null || overlapsKorean(requiredMajor, input.department())) {
        satisfied++;
      } else {
        missingOut.add("전공(" + requiredMajor + ")");
      }
    }
    return total == 0 ? 100 : Math.round(satisfied * 100f / total);
  }

  /** 우대 조건 ↔ 경험 카드 텍스트 일치 비율. 우대 조건이 없으면 100. */
  private int preference(Input input, List<String> matchedOut) {
    if (input.preferences() == null || input.preferences().isEmpty()) {
      return 100;
    }
    if (input.experienceTexts().isEmpty()) {
      return 0;
    }
    String experienceBlob = String.join(" ", input.experienceTexts()).toLowerCase(Locale.ROOT);
    for (String preference : input.preferences()) {
      for (String token : preference.toLowerCase(Locale.ROOT).split("[^a-z0-9가-힣+#.]+")) {
        if (token.length() >= 2 && !token.equals("우대") && experienceBlob.contains(token)) {
          matchedOut.add(preference);
          break;
        }
      }
    }
    return Math.round(matchedOut.size() * 100f / input.preferences().size());
  }

  /** 모집 규모 추정 — 많이 뽑을수록 경쟁이 낮다(가산). 규모를 모르면 중립 50. */
  private int competition(String rawContent) {
    if (rawContent == null) {
      return 50;
    }
    Matcher matcher = Pattern.compile("(\\d{1,4})\\s?명").matcher(rawContent);
    int max = 0;
    while (matcher.find()) {
      max = Math.max(max, Integer.parseInt(matcher.group(1)));
    }
    if (max == 0) {
      return 50;
    }
    return Math.min(100, 30 + max * 7);
  }

  private BigDecimal firstNumber(String text) {
    Matcher matcher = NUMBER.matcher(text);
    return matcher.find() ? new BigDecimal(matcher.group(1)) : null;
  }

  private boolean overlapsKorean(String required, String actual) {
    String cleanRequired = required.replaceAll("(학과|학부|전공|계열)", "");
    String cleanActual = actual.replaceAll("(학과|학부|전공|계열)", "");
    return cleanActual.contains(cleanRequired) || cleanRequired.contains(cleanActual);
  }
}
