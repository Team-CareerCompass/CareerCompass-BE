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

/** #11 재사용 탐지 — 유예 0 으로 두고, 소비된 토큰 재사용이 계보 전체를 폐기하는지 본다. */
@Import(FakeOAuthConfig.class)
@TestPropertySource(properties = "auth.jwt.refresh-grace=0s")
class AuthRefreshReuseIT extends IntegrationTestBase {

  @Autowired private TestRestTemplate rest;
  @Autowired private ObjectMapper objectMapper;

  private ResponseEntity<String> postJson(String path, Map<String, ?> body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
  }

  private String field(ResponseEntity<String> res, String... path) {
    try {
      var node = objectMapper.readTree(res.getBody());
      for (String p : path) {
        node = node.path(p);
      }
      return node.asText();
    } catch (Exception e) {
      throw new IllegalStateException(res.getBody(), e);
    }
  }

  @Test
  @DisplayName("소비된 리프레시의 유예 밖 재사용 → AUTH_INVALID + 같은 계보의 새 토큰도 함께 폐기된다")
  void reuseKillsWholeFamily() {
    ResponseEntity<String> login =
        postJson(
            "/auth/social/kakao",
            Map.of("accessToken", "tok-" + UUID.randomUUID(), "deviceId", "dev-theft"));
    String refresh1 = field(login, "data", "refreshToken");

    ResponseEntity<String> rotated = postJson("/auth/refresh", Map.of("refreshToken", refresh1));
    assertThat(rotated.getStatusCode().value()).isEqualTo(200);
    String refresh2 = field(rotated, "data", "refreshToken");

    // 유예 0s — 소비된 refresh1 재사용은 즉시 도난 취급
    ResponseEntity<String> reuse = postJson("/auth/refresh", Map.of("refreshToken", refresh1));
    assertThat(reuse.getStatusCode().value()).isEqualTo(401);
    assertThat(field(reuse, "error", "code")).isEqualTo("AUTH_INVALID");

    // 계보 전체 폐기 — 정상 발급된 refresh2 도 죽어 있어야 한다
    ResponseEntity<String> victim = postJson("/auth/refresh", Map.of("refreshToken", refresh2));
    assertThat(victim.getStatusCode().value()).isEqualTo(401);
    assertThat(field(victim, "error", "code")).isEqualTo("AUTH_INVALID");
  }
}
