package com.careercompass.common.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 시간 의존 로직(토큰 만료·유예 판정 등)은 이 Clock 만 쓴다 — 테스트에서 교체 가능. */
@Configuration
public class TimeConfig {

  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }
}
