package com.careercompass.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JwtProviderTest {

  private final JwtProperties properties =
      new JwtProperties(
          "unit-test-secret-key-0123456789abcdef",
          Duration.ofMinutes(30),
          Duration.ofDays(14),
          Duration.ofSeconds(30));

  private final JwtProvider provider = new JwtProvider(properties, Clock.systemUTC());

  @Test
  @DisplayName("발급한 액세스 토큰에서 userId 를 되돌려 받는다")
  void roundTrip() {
    String token = provider.createAccessToken(42L);

    assertThat(provider.parseUserId(token)).isEqualTo(42L);
  }

  @Test
  @DisplayName("만료된 토큰은 Expired 예외 — 401 AUTH_REQUIRED 로 이어진다")
  void expiredToken() {
    String token = provider.createAccessToken(42L, Duration.ofSeconds(-10));

    assertThatThrownBy(() -> provider.parseUserId(token))
        .isInstanceOf(JwtProvider.ExpiredAccessTokenException.class);
  }

  @Test
  @DisplayName("변조된 토큰은 Invalid 예외 — 401 AUTH_INVALID 로 이어진다")
  void tamperedToken() {
    String token = provider.createAccessToken(42L);

    assertThatThrownBy(() -> provider.parseUserId(token + "x"))
        .isInstanceOf(JwtProvider.InvalidAccessTokenException.class);
  }

  @Test
  @DisplayName("서명 키가 256bit 미만이면 기동을 거부한다")
  void rejectsShortSecret() {
    JwtProperties shortSecret =
        new JwtProperties("too-short", Duration.ofMinutes(30), Duration.ofDays(14), null);

    assertThatThrownBy(() -> new JwtProvider(shortSecret, Clock.systemUTC()))
        .isInstanceOf(IllegalStateException.class);
  }
}
