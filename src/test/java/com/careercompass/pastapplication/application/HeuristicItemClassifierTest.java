package com.careercompass.pastapplication.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HeuristicItemClassifierTest {

  private final HeuristicItemClassifier classifier = new HeuristicItemClassifier();

  @Test
  @DisplayName("문단별로 분리되고 신호어에 따라 카테고리가 붙는다")
  void classifiesParagraphs() {
    String text =
        """
        제가 이 회사에 지원하게 된 이유는 명확합니다. 지원 동기는 서비스에 대한 애정입니다.

        대학 시절 팀 프로젝트를 진행하며 백엔드를 개발했습니다. 동아리 활동을 하며 협업을 배웠습니다.

        입사 후 포부는 다음과 같습니다. 앞으로 성장하겠습니다.
        """;

    List<ItemClassifier.ClassifiedItem> items = classifier.classify(text);

    assertThat(items).hasSize(3);
    assertThat(items.get(0).category()).isEqualTo("motivation");
    assertThat(items.get(0).confident()).isTrue();
    assertThat(items.get(1).category()).isEqualTo("experience");
    assertThat(items.get(2).category()).isEqualTo("aspiration");
  }

  @Test
  @DisplayName("신호어가 없으면 other + confident=false — FE 가 「확인 필요」로 표시한다")
  void unknownParagraphFallsBackToOther() {
    List<ItemClassifier.ClassifiedItem> items =
        classifier.classify("오늘 날씨가 맑고 산책하기 좋은 하루였습니다. 커피를 마셨습니다.");

    assertThat(items).hasSize(1);
    assertThat(items.get(0).category()).isEqualTo("other");
    assertThat(items.get(0).confident()).isFalse();
  }
}
