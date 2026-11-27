package com.careercompass.auth.jwt;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

/** 액세스 토큰(JWT, HS256) 발급·검증. sub 클레임에 userId 를 싣는다 (#10). */
@Component
public class JwtProvider {

  private final SecretKey key;
  private final JwtProperties properties;
  private final Clock clock;

  public JwtProvider(JwtProperties properties, Clock clock) {
    if (properties.secret() == null
        || properties.secret().getBytes(StandardCharsets.UTF_8).length < 32) {
      throw new IllegalStateException("JWT_SECRET 은 256bit(32byte) 이상이어야 합니다");
    }
    this.key =
        io.jsonwebtoken.security.Keys.hmacShaKeyFor(
            properties.secret().getBytes(StandardCharsets.UTF_8));
    this.properties = properties;
    this.clock = clock;
  }

  public String createAccessToken(Long userId) {
    return createAccessToken(userId, properties.accessTtl());
  }

  public String createAccessToken(Long userId, Duration ttl) {
    Instant now = clock.instant();
    return Jwts.builder()
        // jti — 같은 초에 다시 발급해도 토큰 문자열이 달라야 한다(FE 는 갱신 결과가 이전 토큰과 같으면 세션을 지운다)
        .id(java.util.UUID.randomUUID().toString())
        .subject(String.valueOf(userId))
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plus(ttl)))
        .signWith(key, Jwts.SIG.HS256)
        .compact();
  }

  /**
   * 토큰에서 userId 를 꺼낸다.
   *
   * @throws ExpiredAccessTokenException 만료 — 401 AUTH_REQUIRED 로 나간다
   * @throws InvalidAccessTokenException 서명 무효·형식 오류 — 401 AUTH_INVALID 로 나간다 (#14)
   */
  public Long parseUserId(String token) {
    try {
      String subject =
          Jwts.parser()
              .verifyWith(key)
              .clock(() -> Date.from(clock.instant()))
              .build()
              .parseSignedClaims(token)
              .getPayload()
              .getSubject();
      return Long.parseLong(subject);
    } catch (ExpiredJwtException e) {
      throw new ExpiredAccessTokenException();
    } catch (JwtException | IllegalArgumentException e) {
      throw new InvalidAccessTokenException();
    }
  }

  public static class ExpiredAccessTokenException extends RuntimeException {}

  public static class InvalidAccessTokenException extends RuntimeException {}
}
