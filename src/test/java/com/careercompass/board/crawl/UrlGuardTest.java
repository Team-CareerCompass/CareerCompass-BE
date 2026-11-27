package com.careercompass.board.crawl;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.careercompass.common.exception.BusinessException;
import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UrlGuardTest {

  private final UrlGuard guard =
      new UrlGuard(new CrawlProperties(null, null, null, 0, 0, false, true));

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://127.0.0.1/board",
        "http://localhost:8080/admin",
        "http://192.168.0.10/notice",
        "http://10.0.0.5/internal"
      })
  @DisplayName("SSRF 차단 — 사설·루프백 대역은 거부한다 (#26)")
  void blocksPrivateAddresses(String url) {
    assertThatThrownBy(() -> guard.ensurePublicAddress(URI.create(url)))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  @DisplayName("스킴 검증 — http/https 외에는 거부, 형식 오류 거부")
  void schemeValidation() {
    assertThatThrownBy(() -> guard.validateFormat("ftp://example.com/board"))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> guard.validateFormat("not a url"))
        .isInstanceOf(BusinessException.class);
    assertThatCode(() -> guard.validateFormat("https://example.com/board"))
        .doesNotThrowAnyException();
  }
}
