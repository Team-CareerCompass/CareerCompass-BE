package com.careercompass.analysis.gateway;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CareerCompass-AI 서버 연동 설정 (#35). base-url 이 비어 있으면 온프레미스 휴리스틱 구현이 대신 돈다.
 *
 * @param baseUrl AI 서버 주소 (예: compose 안 http://ai:8000, 로컬 http://localhost:8100). 계약은 AI 저장소
 *     docs/AI_CONTRACT_v0.2.md
 * @param promptVersion 프롬프트 버전 — 요청 헤더로 전송, 재파싱 판단에 사용 (#31)
 * @param dailyCallBudget 일일 호출 상한 — 초과 시 LLM_UNAVAILABLE (비용 통제)
 */
@ConfigurationProperties(prefix = "app.ai")
public record AiProperties(
    String baseUrl, Duration timeout, int maxRetries, String promptVersion, int dailyCallBudget) {

  public AiProperties {
    if (timeout == null) {
      timeout = Duration.ofSeconds(20);
    }
    if (maxRetries <= 0) {
      maxRetries = 2;
    }
    if (promptVersion == null || promptVersion.isBlank()) {
      promptVersion = "v1";
    }
    if (dailyCallBudget <= 0) {
      dailyCallBudget = 2000;
    }
  }
}
