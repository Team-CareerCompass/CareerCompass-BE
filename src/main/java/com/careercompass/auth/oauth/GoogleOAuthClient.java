package com.careercompass.auth.oauth;

import com.careercompass.common.api.ErrorCode;
import com.careercompass.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 구글 로그인 검증. 앱(Credential Manager)은 <b>ID 토큰(JWT)</b>을 {@code accessToken} 필드에 담아 보낸다 — ID 토큰은
 * userinfo 엔드포인트의 Bearer 로 쓸 수 없으므로 tokeninfo 로 검증하고 {@code aud} 가 우리 웹 클라이언트 ID 인지 확인한다. 점이 두 개 없는
 * 값은 예전처럼 OAuth access token 으로 보고 userinfo 를 부른다.
 */
@Component
public class GoogleOAuthClient implements OAuthClient {

  private static final Logger log = LoggerFactory.getLogger(GoogleOAuthClient.class);

  private final RestClient restClient;
  private final Set<String> allowedAudiences;

  public GoogleOAuthClient(
      RestClient.Builder builder,
      @Value("${auth.google.client-ids:${GOOGLE_WEB_CLIENT_ID:}}") String clientIds) {
    this.restClient = builder.build();
    this.allowedAudiences =
        Arrays.stream(clientIds.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
    if (allowedAudiences.isEmpty()) {
      log.warn("GOOGLE_WEB_CLIENT_ID 미설정 — 구글 ID 토큰의 aud 를 검사하지 않는다(개발 전용). 운영에서는 반드시 설정한다.");
    }
  }

  @Override
  public String provider() {
    return "google";
  }

  @Override
  public OAuthProfile fetch(String token) {
    JsonNode body = looksLikeJwt(token) ? verifyIdToken(token) : userInfo(token);
    if (body == null || !body.path("sub").isTextual()) {
      throw new BusinessException(ErrorCode.AUTH_INVALID, "구글 토큰 검증에 실패했습니다");
    }
    String email = body.path("email").isTextual() ? body.path("email").asText() : null;
    String name = body.path("name").isTextual() ? body.path("name").asText() : null;
    return new OAuthProfile(body.path("sub").asText(), email, name);
  }

  private static boolean looksLikeJwt(String token) {
    return token != null && token.chars().filter(c -> c == '.').count() == 2;
  }

  private JsonNode verifyIdToken(String idToken) {
    JsonNode body =
        call(
            () ->
                restClient
                    .get()
                    .uri("https://oauth2.googleapis.com/tokeninfo?id_token={t}", idToken)
                    .retrieve()
                    .body(JsonNode.class));
    String aud = body == null ? null : body.path("aud").asText(null);
    if (!allowedAudiences.isEmpty() && (aud == null || !allowedAudiences.contains(aud))) {
      // 다른 앱이 받은 구글 토큰으로 우리 계정에 들어오는 것을 막는다
      throw new BusinessException(ErrorCode.AUTH_INVALID, "구글 토큰 검증에 실패했습니다");
    }
    return body;
  }

  private JsonNode userInfo(String accessToken) {
    return call(
        () ->
            restClient
                .get()
                .uri("https://openidconnect.googleapis.com/v1/userinfo")
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .body(JsonNode.class));
  }

  private JsonNode call(java.util.function.Supplier<JsonNode> request) {
    try {
      return request.get();
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().is4xxClientError()) {
        throw new BusinessException(ErrorCode.AUTH_INVALID, "구글 토큰 검증에 실패했습니다");
      }
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "구글 API 호출에 실패했습니다");
    }
  }
}
