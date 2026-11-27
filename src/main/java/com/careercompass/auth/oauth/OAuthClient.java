package com.careercompass.auth.oauth;

/** provider accessToken 을 사업자 API 로 서버에서 직접 검증한다 — 클라이언트 주장 신뢰 금지 (#10). */
public interface OAuthClient {

  /** kakao | google */
  String provider();

  /**
   * @throws com.careercompass.common.exception.BusinessException AUTH_INVALID — 토큰이 사업자에서 거부된 경우
   */
  OAuthProfile fetch(String accessToken);
}
