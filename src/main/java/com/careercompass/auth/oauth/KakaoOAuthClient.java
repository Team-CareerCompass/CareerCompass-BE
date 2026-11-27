package com.careercompass.auth.oauth;

import com.careercompass.common.api.ErrorCode;
import com.careercompass.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class KakaoOAuthClient implements OAuthClient {

  private final RestClient restClient;

  public KakaoOAuthClient(RestClient.Builder builder) {
    this.restClient = builder.baseUrl("https://kapi.kakao.com").build();
  }

  @Override
  public String provider() {
    return "kakao";
  }

  @Override
  public OAuthProfile fetch(String accessToken) {
    JsonNode body;
    try {
      body =
          restClient
              .get()
              .uri("/v2/user/me")
              .header("Authorization", "Bearer " + accessToken)
              .retrieve()
              .body(JsonNode.class);
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().is4xxClientError()) {
        throw new BusinessException(ErrorCode.AUTH_INVALID, "카카오 토큰 검증에 실패했습니다");
      }
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "카카오 API 호출에 실패했습니다");
    }
    if (body == null || body.path("id").isMissingNode()) {
      throw new BusinessException(ErrorCode.AUTH_INVALID, "카카오 토큰 검증에 실패했습니다");
    }
    String providerId = body.path("id").asText();
    JsonNode account = body.path("kakao_account");
    String email = account.path("email").isTextual() ? account.path("email").asText() : null;
    JsonNode nickname = account.path("profile").path("nickname");
    String name = nickname.isTextual() ? nickname.asText() : null;
    return new OAuthProfile(providerId, email, name);
  }
}
