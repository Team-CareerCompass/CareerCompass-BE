package com.careercompass.board.crawl;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 수집 예절·안전 설정 (#26).
 *
 * @param userAgent 식별 가능한 UA (연락처 포함)
 * @param connectTimeout 연결 타임아웃
 * @param readTimeout 응답 타임아웃 — 감지(detect)는 일반 API 보다 길게 허용한다 (#22)
 * @param maxBodyBytes 응답 크기 상한
 * @param minDomainIntervalMs 동일 도메인 최소 요청 간격 (동시 1건 + 간격)
 * @param allowPrivateAddresses 사설 IP 허용 여부 — 운영은 false 고정, 로컬 개발·테스트만 true (SSRF 차단)
 * @param schedulerEnabled 주기 수집 스케줄러 스위치 (테스트에서 끈다)
 */
@ConfigurationProperties(prefix = "app.crawl")
public record CrawlProperties(
    String userAgent,
    Duration connectTimeout,
    Duration readTimeout,
    long maxBodyBytes,
    long minDomainIntervalMs,
    boolean allowPrivateAddresses,
    boolean schedulerEnabled) {

  public CrawlProperties {
    if (userAgent == null || userAgent.isBlank()) {
      userAgent = "CareerCompassBot/0.1 (+https://github.com/Team-CareerCompass/CareerCompass-BE)";
    }
    if (connectTimeout == null) {
      connectTimeout = Duration.ofSeconds(5);
    }
    if (readTimeout == null) {
      readTimeout = Duration.ofSeconds(15);
    }
    if (maxBodyBytes <= 0) {
      maxBodyBytes = 2L * 1024 * 1024;
    }
    if (minDomainIntervalMs <= 0) {
      minDomainIntervalMs = 1000;
    }
  }
}
