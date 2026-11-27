package com.careercompass.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/** API 통합 테스트 공통 헬퍼 — fake 소셜 로그인으로 세션을 만들고 Bearer 요청을 보낸다. */
@Import(FakeOAuthConfig.class)
public abstract class ApiITBase extends IntegrationTestBase {

  @Autowired protected TestRestTemplate rest;
  @Autowired protected ObjectMapper objectMapper;

  protected record Session(String accessToken, String refreshToken, long userId) {}

  protected Session login() {
    ResponseEntity<String> res =
        call(
            HttpMethod.POST,
            "/auth/social/kakao",
            Map.of(
                "accessToken", "tok-" + UUID.randomUUID(), "deviceId", "dev-" + UUID.randomUUID()),
            null);
    JsonNode data = json(res).path("data");
    return new Session(
        data.path("accessToken").asText(),
        data.path("refreshToken").asText(),
        data.path("user").path("id").asLong());
  }

  protected ResponseEntity<String> call(
      HttpMethod method, String path, Object body, String bearer) {
    HttpHeaders headers = new HttpHeaders();
    if (body != null) {
      headers.setContentType(MediaType.APPLICATION_JSON);
    }
    if (bearer != null) {
      headers.setBearerAuth(bearer);
    }
    return rest.exchange(path, method, new HttpEntity<>(body, headers), String.class);
  }

  protected ResponseEntity<String> upload(
      String path, String filename, byte[] content, String label, String bearer) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);
    headers.setBearerAuth(bearer);
    MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
    parts.add(
        "file",
        new ByteArrayResource(content) {
          @Override
          public String getFilename() {
            return filename;
          }
        });
    parts.add("label", label);
    return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(parts, headers), String.class);
  }

  protected JsonNode json(ResponseEntity<String> res) {
    try {
      return objectMapper.readTree(res.getBody());
    } catch (Exception e) {
      throw new IllegalStateException("응답 파싱 실패: " + res.getBody(), e);
    }
  }
}
