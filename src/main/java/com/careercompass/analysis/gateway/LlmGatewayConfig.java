package com.careercompass.analysis.gateway;

import com.careercompass.common.privacy.PrivacyMasker;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LLM 게이트웨이 구현을 하나만 고른다 (#64). {@code app.ai.base-url}(환경변수 {@code AI_BASE_URL})이 비어 있으면 휴리스틱, 있으면
 * CareerCompass-AI HTTP 게이트웨이.
 *
 * <p>예전에는 두 구현이 각자 {@code @ConditionalOnProperty} 로 켜졌는데, 빈 문자열 havingValue 는 「값이 false 가 아님」으로만
 * 판정돼 주소를 주면 두 빈이 함께 떠 주입이 모호해졌다. 선택을 한 곳에 모으고 무엇이 켜졌는지 기동 로그에 남긴다.
 */
@Configuration
public class LlmGatewayConfig {

  private static final Logger log = LoggerFactory.getLogger(LlmGatewayConfig.class);

  @Bean
  LlmGateway llmGateway(
      AiProperties properties,
      ObjectMapper objectMapper,
      PrivacyMasker privacyMasker,
      MeterRegistry meterRegistry) {
    if (properties.baseUrl() == null || properties.baseUrl().isBlank()) {
      log.info("LlmGateway = HeuristicLlmGateway — AI 서버 미연결(휴리스틱 폴백). 연결하려면 AI_BASE_URL 을 준다.");
      return new HeuristicLlmGateway();
    }
    log.info(
        "LlmGateway = HttpLlmGateway — AI 서버 {} (promptVersion={}, timeout={}, dailyCallBudget={})",
        properties.baseUrl(),
        properties.promptVersion(),
        properties.timeout(),
        properties.dailyCallBudget());
    return new HttpLlmGateway(properties, objectMapper, privacyMasker, meterRegistry);
  }
}
