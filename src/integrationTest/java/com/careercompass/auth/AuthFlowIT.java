package com.careercompass.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.careercompass.auth.jwt.JwtProvider;
import com.careercompass.support.FakeOAuthConfig;
import com.careercompass.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
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
import org.springframework.jdbc.core.JdbcTemplate;

/** M1 통합 — 소셜 로그인(#10)·재발급 회전(#11)·로그아웃(#12)·지문 기기 등록(#13)·필터 계약(#14). */
@Import(FakeOAuthConfig.class)
class AuthFlowIT extends IntegrationTestBase {

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JwtProvider jwtProvider;

  private ResponseEntity<String> login(String provider, String token, String deviceId) {
    return postJson(
        "/auth/social/" + provider,
        Map.of("accessToken", token, "deviceId", deviceId, "fcmToken", "fcm-" + deviceId),
        null);
  }

  private ResponseEntity<String> postJson(String path, Map<String, ?> body, String bearer) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (bearer != null) {
      headers.setBearerAuth(bearer);
    }
    return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
  }

  private JsonNode json(ResponseEntity<String> res) {
    try {
      return objectMapper.readTree(res.getBody());
    } catch (Exception e) {
      throw new IllegalStateException(res.getBody(), e);
    }
  }

  @Test
  @DisplayName("최초 소셜 로그인 — 201, 사용자 생성, onboardingDone=false, 재로그인 시 같은 사용자")
  void firstLoginCreatesUser() {
    String token = "tok-" + UUID.randomUUID();

    ResponseEntity<String> first = login("kakao", token, "dev-1");
    assertThat(first.getStatusCode().value()).isEqualTo(201);
    JsonNode data = json(first).path("data");
    assertThat(data.path("accessToken").asText()).isNotBlank();
    assertThat(data.path("refreshToken").asText()).isNotBlank();
    assertThat(data.path("user").path("onboardingDone").asBoolean()).isFalse();

    ResponseEntity<String> second = login("kakao", token, "dev-1");
    assertThat(json(second).path("data").path("user").path("id").asLong())
        .isEqualTo(data.path("user").path("id").asLong());
  }

  @Test
  @DisplayName("지원하지 않는 provider 는 INVALID_INPUT(field=provider)")
  void unknownProviderRejected() {
    ResponseEntity<String> res = login("naver", "tok-" + UUID.randomUUID(), "dev-1");

    assertThat(res.getStatusCode().value()).isEqualTo(400);
    assertThat(json(res).path("error").path("code").asText()).isEqualTo("INVALID_INPUT");
    assertThat(json(res).path("error").path("field").asText()).isEqualTo("provider");
  }

  @Test
  @DisplayName("사업자가 거부한 소셜 토큰은 AUTH_INVALID")
  void providerRejectionMapsToAuthInvalid() {
    ResponseEntity<String> res = login("kakao", "bad-" + UUID.randomUUID(), "dev-1");

    assertThat(res.getStatusCode().value()).isEqualTo(401);
    assertThat(json(res).path("error").path("code").asText()).isEqualTo("AUTH_INVALID");
  }

  @Test
  @DisplayName("재발급은 회전 — 새 쌍이 나오고, 회전 직후(유예 안) 옛 토큰 재사용은 경합으로 보고 성공한다")
  void refreshRotatesAndGraceAllowsRace() {
    String refresh1 =
        json(login("kakao", "tok-" + UUID.randomUUID(), "dev-r"))
            .path("data")
            .path("refreshToken")
            .asText();

    ResponseEntity<String> rotated =
        postJson("/auth/refresh", Map.of("refreshToken", refresh1), null);
    assertThat(rotated.getStatusCode().value()).isEqualTo(200);
    String refresh2 = json(rotated).path("data").path("refreshToken").asText();
    assertThat(refresh2).isNotEqualTo(refresh1);

    // single-flight 가 깨진 동시 재발급 시나리오 — 유예(30s) 안이므로 성공해야 한다 (#11)
    ResponseEntity<String> raced =
        postJson("/auth/refresh", Map.of("refreshToken", refresh1), null);
    assertThat(raced.getStatusCode().value()).isEqualTo(200);

    // 회전으로 받은 새 토큰도 정상 동작
    ResponseEntity<String> next = postJson("/auth/refresh", Map.of("refreshToken", refresh2), null);
    assertThat(next.getStatusCode().value()).isEqualTo(200);
  }

  @Test
  @DisplayName("알 수 없는 리프레시 토큰은 AUTH_INVALID")
  void unknownRefreshRejected() {
    ResponseEntity<String> res =
        postJson("/auth/refresh", Map.of("refreshToken", "no-such-token"), null);

    assertThat(res.getStatusCode().value()).isEqualTo(401);
    assertThat(json(res).path("error").path("code").asText()).isEqualTo("AUTH_INVALID");
  }

  @Test
  @DisplayName("로그아웃 — 이 기기의 리프레시가 죽고 FCM·지문 등록이 해제되며, 재요청도 멱등 200")
  void logoutRevokesDeviceSession() {
    String token = "tok-" + UUID.randomUUID();
    JsonNode data = json(login("kakao", token, "dev-out")).path("data");
    String access = data.path("accessToken").asText();
    String refresh = data.path("refreshToken").asText();
    long userId = data.path("user").path("id").asLong();

    postJson("/auth/biometric/register", Map.of("deviceId", "dev-out"), access);

    ResponseEntity<String> logout = postJson("/auth/logout", Map.of("deviceId", "dev-out"), access);
    assertThat(logout.getStatusCode().value()).isEqualTo(200);

    ResponseEntity<String> refreshAfter =
        postJson("/auth/refresh", Map.of("refreshToken", refresh), null);
    assertThat(refreshAfter.getStatusCode().value()).isEqualTo(401);
    assertThat(json(refreshAfter).path("error").path("code").asText()).isEqualTo("AUTH_INVALID");

    Map<String, Object> device =
        jdbc.queryForMap(
            "select fcm_token, biometric_enabled from user_device where user_id = ? and device_id = ?",
            userId,
            "dev-out");
    assertThat(device.get("fcm_token")).isNull();
    assertThat(device.get("biometric_enabled")).isEqualTo(false);

    // 멱등 — 이미 로그아웃된 상태의 재요청도 200 (#12)
    ResponseEntity<String> again = postJson("/auth/logout", Map.of("deviceId", "dev-out"), access);
    assertThat(again.getStatusCode().value()).isEqualTo(200);
  }

  @Test
  @DisplayName("인증 실패 3종이 전부 계약 본문으로 나간다 — 없음·만료는 AUTH_REQUIRED, 무효는 AUTH_INVALID (#14)")
  void authFailureContract() {
    ResponseEntity<String> noToken = rest.getForEntity("/postings", String.class);
    assertThat(noToken.getStatusCode().value()).isEqualTo(401);
    assertThat(json(noToken).path("error").path("code").asText()).isEqualTo("AUTH_REQUIRED");

    HttpHeaders garbage = new HttpHeaders();
    garbage.setBearerAuth("garbage.token.value");
    ResponseEntity<String> invalid =
        rest.exchange("/postings", HttpMethod.GET, new HttpEntity<>(garbage), String.class);
    assertThat(invalid.getStatusCode().value()).isEqualTo(401);
    assertThat(json(invalid).path("error").path("code").asText()).isEqualTo("AUTH_INVALID");

    HttpHeaders expired = new HttpHeaders();
    expired.setBearerAuth(jwtProvider.createAccessToken(1L, Duration.ofSeconds(-10)));
    ResponseEntity<String> expiredRes =
        rest.exchange("/postings", HttpMethod.GET, new HttpEntity<>(expired), String.class);
    assertThat(expiredRes.getStatusCode().value()).isEqualTo(401);
    assertThat(json(expiredRes).path("error").path("code").asText()).isEqualTo("AUTH_REQUIRED");
  }

  @Test
  @DisplayName("한 기기를 두 계정이 써도 지문 등록이 서로를 덮지 않는다 (#13)")
  void biometricPerAccountPerDevice() {
    String device = "shared-device-" + UUID.randomUUID();
    JsonNode a = json(login("kakao", "tok-" + UUID.randomUUID(), device)).path("data");
    JsonNode b = json(login("google", "tok-" + UUID.randomUUID(), device)).path("data");

    postJson(
        "/auth/biometric/register", Map.of("deviceId", device), a.path("accessToken").asText());
    postJson(
        "/auth/biometric/register", Map.of("deviceId", device), b.path("accessToken").asText());

    Integer enabledCount =
        jdbc.queryForObject(
            "select count(*) from user_device where device_id = ? and biometric_enabled",
            Integer.class,
            device);
    assertThat(enabledCount).isEqualTo(2);
  }

  @Test
  @DisplayName("지문 등록은 인증 필요 — 토큰 없이 부르면 401")
  void biometricRequiresAuth() {
    ResponseEntity<String> res =
        postJson("/auth/biometric/register", Map.of("deviceId", "dev-x"), null);

    assertThat(res.getStatusCode().value()).isEqualTo(401);
  }
}
