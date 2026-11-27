package com.careercompass.auth.jwt;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT·리프레시 정책 값. 서명 키는 환경 변수로만 주입한다 (#1).
 *
 * @param secret HS256 서명 키 (256bit 이상)
 * @param accessTtl 액세스 토큰 수명
 * @param refreshTtl 리프레시 토큰 수명
 * @param refreshGrace 회전 직후 재사용을 도난으로 보지 않는 유예 시간 (#11 grace window)
 */
@ConfigurationProperties(prefix = "auth.jwt")
public record JwtProperties(
    String secret, Duration accessTtl, Duration refreshTtl, Duration refreshGrace) {

  public JwtProperties {
    if (accessTtl == null) {
      accessTtl = Duration.ofMinutes(30);
    }
    if (refreshTtl == null) {
      refreshTtl = Duration.ofDays(14);
    }
    if (refreshGrace == null) {
      refreshGrace = Duration.ofSeconds(30);
    }
  }
}
