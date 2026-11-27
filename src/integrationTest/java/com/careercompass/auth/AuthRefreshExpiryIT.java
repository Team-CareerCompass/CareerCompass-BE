package com.careercompass.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.careercompass.support.FakeOAuthConfig;
import com.careercompass.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/** #11 만료 시나리오 — 수명 0 인 리프레시는 즉시 AUTH_INVALID. FE 는 이 코드로 세션 만료를 판정한다. */
@Import(FakeOAuthConfig.class)
@TestPropertySource(properties = "auth.jwt.refresh-ttl=0s")
class AuthRefreshExpiryIT extends IntegrationTestBase {

  @Autowired private TestRestTemplate rest;
  @Autowired private ObjectMapper objectMapper;

  @Test
  @DisplayName("만료된 리프레시 토큰은 AUTH_INVALID")
  void expiredRefreshRejected() throws Exception {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<String> login =
        rest.exchange(
            "/auth/social/kakao",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of("accessToken", "tok-" + UUID.randomUUID(), "deviceId", "dev-exp"), headers),
            String.class);
    String refresh =
        objectMapper.readTree(login.getBody()).path("data").path("refreshToken").asText();

    ResponseEntity<String> res =
        rest.exchange(
            "/auth/refresh",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("refreshToken", refresh), headers),
            String.class);

    assertThat(res.getStatusCode().value()).isEqualTo(401);
    assertThat(objectMapper.readTree(res.getBody()).path("error").path("code").asText())
        .isEqualTo("AUTH_INVALID");
  }
}
