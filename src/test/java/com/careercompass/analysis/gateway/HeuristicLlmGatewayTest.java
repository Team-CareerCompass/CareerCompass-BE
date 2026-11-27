package com.careercompass.analysis.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HeuristicLlmGatewayTest {

  private final HeuristicLlmGateway gateway = new HeuristicLlmGateway();

  private static final String SAMPLE =
      """
      지원 자격: 4년제 대학 재학생, 학점 3.0 이상, 컴퓨터공학과 전공자, 3학년 이상
      백엔드 서버를 함께 만들 Spring 개발자를 찾습니다. 백엔드 실무를 경험할 수 있습니다.
      우대사항:
      - Spring 백엔드 개발 경험 우대
      - AWS 인프라 경험 우대
      모집 인원: 5명
      접수기간: 2026.11.01 ~ 2026.12.31 마감
      지원서 항목:
      1. 지원 동기를 작성해 주세요 (500자)
      2. 본인의 강점을 기술해 주세요 (300자)
      """;

  @Test
  @DisplayName("샘플 공고에서 키워드·자격·우대·마감일·질문이 추출된다 (#31)")
  void parsesSamplePosting() {
    LlmGateway.ParsedPosting parsed = gateway.parsePosting("2026 백엔드 개발자 신입 채용", SAMPLE);

    assertThat(parsed.keywords()).hasSizeGreaterThanOrEqualTo(3).contains("백엔드");
    assertThat(parsed.type()).isEqualTo("recruit");
    assertThat(parsed.qualificationGpa()).contains("3.0");
    assertThat(parsed.qualificationYear()).contains("3");
    assertThat(parsed.qualificationMajor()).contains("컴퓨터공학과");
    assertThat(parsed.preferences()).hasSize(2);
    assertThat(parsed.dueDate()).isEqualTo(LocalDate.of(2026, 12, 31));
    assertThat(parsed.formQuestions()).hasSize(2);
    assertThat(parsed.formQuestions().get(0).maxChars()).isEqualTo(500);
  }

  @Test
  @DisplayName("핵심 키워드 3개 미만이면 파싱 실패 (#31 실패 규칙)")
  void failsWhenTooFewKeywords() {
    assertThatThrownBy(() -> gateway.parsePosting("공지", "안내."))
        .isInstanceOf(LlmGateway.ParsingFailedException.class);
  }

  @Test
  @DisplayName("「상시」 모집은 마감일 null, 장학 키워드는 scholarship 재분류")
  void openEndedAndReclassify() {
    LlmGateway.ParsedPosting parsed =
        gateway.parsePosting(
            "2026 성적우수 장학금 신청",
            "성적우수 장학생 대상 상시 모집입니다. 성적우수 기준은 직전 학기 평점입니다."
                + " 등록금 전액 유형과 등록금 반액 유형이 있습니다. 장학생 선발 후 장학생 혜택은 별도입니다.");

    assertThat(parsed.type()).isEqualTo("scholarship");
    assertThat(parsed.dueDate()).isNull();
  }
}
