package com.careercompass.analysis.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SuitabilityCalculatorTest {

  private final SuitabilityCalculator calculator =
      new SuitabilityCalculator(new AnalysisProperties(40, 30, 20, 10, true));

  private SuitabilityCalculator.Input baseInput() {
    return new SuitabilityCalculator.Input(
        List.of("백엔드", "Spring", "AWS"),
        "3학년 이상",
        "학점 3.0 이상",
        "컴퓨터공학과",
        List.of("Spring 백엔드 개발 경험 우대", "AWS 인프라 경험 우대"),
        "신입 5명 모집",
        List.of("backend", "백엔드 개발", "Spring"),
        "컴퓨터공학부",
        new BigDecimal("3.5"),
        List.of("CareerCompass Spring 백엔드 API 개발"));
  }

  @Test
  @DisplayName("AI 분야 유사도 점수가 있으면 field_similarity 축만 그 값 — 코멘트 근거는 글자 비교 그대로 (AI 계약 §5)")
  void aiFieldScoreReplacesOnlyFieldAxis() {
    SuitabilityCalculator.Input base = baseInput();
    SuitabilityCalculator.Input withAi =
        new SuitabilityCalculator.Input(
            base.keywords(),
            base.qualificationYear(),
            base.qualificationGpa(),
            base.qualificationMajor(),
            base.preferences(),
            base.rawContent(),
            base.interestTexts(),
            base.department(),
            base.gpa(),
            base.experienceTexts(),
            37);

    SuitabilityCalculator.Result keyword = calculator.calculate(base);
    SuitabilityCalculator.Result ai = calculator.calculate(withAi);

    assertThat(ai.breakdown().get(0).score()).isEqualTo(37);
    assertThat(ai.breakdown().subList(1, 4)).isEqualTo(keyword.breakdown().subList(1, 4));
    assertThat(ai.matchedKeywords()).isEqualTo(keyword.matchedKeywords());
  }

  @Test
  @DisplayName("결정성 — 같은 입력이면 같은 점수 (#32)")
  void deterministic() {
    SuitabilityCalculator.Result first = calculator.calculate(baseInput());
    SuitabilityCalculator.Result second = calculator.calculate(baseInput());

    assertThat(first.total()).isEqualTo(second.total());
    assertThat(first.breakdown()).isEqualTo(second.breakdown());
  }

  @Test
  @DisplayName("breakdown 가중 평균이 종합 점수와 일치하고 축 이름·가중치는 스펙 고정")
  void breakdownConsistency() {
    SuitabilityCalculator.Result result = calculator.calculate(baseInput());

    int weighted = 0;
    int weightSum = 0;
    for (SuitabilityCalculator.AxisScore axis : result.breakdown()) {
      weighted += axis.score() * axis.weight();
      weightSum += axis.weight();
    }
    assertThat(weightSum).isEqualTo(100);
    assertThat(result.total()).isEqualTo(Math.round(weighted / 100f));
    assertThat(result.breakdown())
        .extracting(SuitabilityCalculator.AxisScore::axis)
        .containsExactly("field_similarity", "qualification", "preference", "competition");
  }

  @Test
  @DisplayName("미기입은 충족 간주 — 학점 요건이 있어도 사용자 학점이 없으면 감점하지 않는다 (F3-2)")
  void unknownProfileValuesCountAsSatisfied() {
    SuitabilityCalculator.Input input =
        new SuitabilityCalculator.Input(
            List.of("백엔드", "Spring", "AWS"),
            null,
            "학점 4.0 이상",
            null,
            List.of(),
            null,
            List.of("백엔드"),
            null,
            null, // 학점 미기입
            List.of());

    SuitabilityCalculator.Result result = calculator.calculate(input);

    SuitabilityCalculator.AxisScore qualification =
        result.breakdown().stream()
            .filter(a -> a.axis().equals("qualification"))
            .findFirst()
            .orElseThrow();
    assertThat(qualification.score()).isEqualTo(100);
  }

  @Test
  @DisplayName("학점 미달은 감점되고 missing 사유에 남는다 — 코멘트(#33) 재료")
  void gpaShortfallDetected() {
    SuitabilityCalculator.Input input =
        new SuitabilityCalculator.Input(
            List.of("백엔드", "Spring", "AWS"),
            null,
            "학점 4.0 이상",
            null,
            List.of(),
            null,
            List.of("백엔드"),
            null,
            new BigDecimal("3.2"),
            List.of());

    SuitabilityCalculator.Result result = calculator.calculate(input);

    assertThat(result.missingQualifications()).anyMatch(m -> m.contains("학점"));
  }

  @Test
  @DisplayName("레이블 경계 — 80/60/40 (F3-2)")
  void labelBoundaries() {
    assertThat(SuitabilityCalculator.label(80)).isEqualTo("very_suitable");
    assertThat(SuitabilityCalculator.label(79)).isEqualTo("suitable");
    assertThat(SuitabilityCalculator.label(60)).isEqualTo("suitable");
    assertThat(SuitabilityCalculator.label(59)).isEqualTo("normal");
    assertThat(SuitabilityCalculator.label(40)).isEqualTo("normal");
    assertThat(SuitabilityCalculator.label(39)).isEqualTo("low");
  }
}
