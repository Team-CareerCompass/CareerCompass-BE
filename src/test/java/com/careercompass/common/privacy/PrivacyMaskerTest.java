package com.careercompass.common.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PrivacyMaskerTest {

  private final PrivacyMasker masker = new PrivacyMasker();

  @Test
  @DisplayName("이메일·전화번호·주민번호가 외부 전송 전에 치환된다 (#48, docs/privacy.md)")
  void masksIdentifiers() {
    String masked =
        masker.mask("연락처는 010-1234-5678 이고 메일은 tak@konkuk.ac.kr, 주민번호 990101-1234567 입니다.");

    assertThat(masked).doesNotContain("010-1234-5678");
    assertThat(masked).doesNotContain("tak@konkuk.ac.kr");
    assertThat(masked).doesNotContain("990101-1234567");
    assertThat(masked).contains("[전화번호]").contains("[이메일]").contains("[주민번호]");
  }

  @Test
  @DisplayName("일반 텍스트는 그대로 보존된다")
  void preservesNormalText() {
    String text = "Spring 백엔드 프로젝트를 3개월간 진행했습니다.";

    assertThat(masker.mask(text)).isEqualTo(text);
  }
}
