package com.careercompass.pastapplication.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 키워드 휴리스틱 분류 — 문단 단위로 나누고 카테고리 신호어를 점수화한다. 점수 2 이상이면 confident, 1 이면 해당 카테고리에 confident=false, 0
 * 이면 other/confident=false (FE 가 「확인 필요」로 표시).
 */
@Component
public class HeuristicItemClassifier implements ItemClassifier {

  private static final Map<String, List<String>> SIGNALS = new LinkedHashMap<>();

  static {
    SIGNALS.put(
        "motivation",
        List.of("지원 동기", "지원동기", "지원하게 된", "지원한 이유", "입사를 희망", "관심을 갖게", "지원하였습니다", "지원했습니다"));
    SIGNALS.put(
        "background", List.of("성장 과정", "성장과정", "성장 배경", "성장배경", "어린 시절", "가치관", "부모님", "학창 시절"));
    SIGNALS.put(
        "experience",
        List.of("프로젝트를 진행", "프로젝트에서", "경험이 있습니다", "활동을 하며", "수행했습니다", "개발했습니다", "참여했습니다", "동아리"));
    SIGNALS.put("competency", List.of("강점", "역량", "능력", "자신 있습니다", "숙련", "전문성", "기술 스택", "잘합니다"));
    SIGNALS.put(
        "aspiration", List.of("입사 후", "입사 뒤", "포부", "앞으로", "기여하겠습니다", "성장하겠습니다", "목표는", "되고 싶습니다"));
  }

  @Override
  public List<ClassifiedItem> classify(String text) {
    List<ClassifiedItem> items = new ArrayList<>();
    for (String paragraph : splitParagraphs(text)) {
      String best = "other";
      int bestScore = 0;
      for (Map.Entry<String, List<String>> entry : SIGNALS.entrySet()) {
        int score = 0;
        for (String signal : entry.getValue()) {
          if (paragraph.contains(signal)) {
            score++;
          }
        }
        if (score > bestScore) {
          best = entry.getKey();
          bestScore = score;
        }
      }
      if (bestScore == 0) {
        items.add(new ClassifiedItem("other", paragraph, false));
      } else {
        items.add(new ClassifiedItem(best, paragraph, bestScore >= 2));
      }
    }
    return items;
  }

  private List<String> splitParagraphs(String text) {
    List<String> paragraphs = new ArrayList<>();
    for (String chunk : text.split("\\n\\s*\\n")) {
      String trimmed = chunk.strip();
      if (trimmed.length() >= 20) {
        paragraphs.add(trimmed);
      }
    }
    if (paragraphs.isEmpty() && !text.isBlank()) {
      paragraphs.add(text.strip());
    }
    return paragraphs;
  }
}
