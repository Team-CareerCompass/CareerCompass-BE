package com.careercompass.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.careercompass.support.ApiITBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/** M2 — 프로필(#15)·희망 직무/태그(#16)·메타 목록(#17). */
class ProfileFlowIT extends ApiITBase {

  @Test
  @DisplayName("온보딩 판정 — 학교·학과(Step1) + 희망 직무 1개 이상(Step2)이면 onboardingDone=true")
  void onboardingJudgment() {
    Session session = login();

    JsonNode initial = json(call(HttpMethod.GET, "/users/me", null, session.accessToken()));
    assertThat(initial.path("data").path("onboardingDone").asBoolean()).isFalse();

    ResponseEntity<String> afterPatch =
        call(
            HttpMethod.PATCH,
            "/users/me",
            Map.of("school", "건국대학교", "department", "컴퓨터공학부", "gpa", 3.8, "gradYear", 2027),
            session.accessToken());
    assertThat(json(afterPatch).path("data").path("onboardingDone").asBoolean())
        .as("Step2 전이므로 아직 false")
        .isFalse();

    ResponseEntity<String> afterInterests =
        call(
            HttpMethod.PUT,
            "/users/me/job-interests",
            Map.of(
                "interests",
                List.of(
                    Map.of("code", "backend", "priority", 1),
                    Map.of("code", "ai_ml", "priority", 2))),
            session.accessToken());
    JsonNode data = json(afterInterests).path("data");
    assertThat(data.path("onboardingDone").asBoolean()).isTrue();
    assertThat(data.path("completion").asInt()).isGreaterThanOrEqualTo(55);
    assertThat(data.path("jobInterests")).hasSize(2);
  }

  @Test
  @DisplayName("PATCH 검증 — 학점 4.6 은 INVALID_INPUT(field=gpa)")
  void gpaValidation() {
    Session session = login();

    ResponseEntity<String> res =
        call(HttpMethod.PATCH, "/users/me", Map.of("gpa", 4.6), session.accessToken());

    assertThat(res.getStatusCode().value()).isEqualTo(400);
    assertThat(json(res).path("error").path("code").asText()).isEqualTo("INVALID_INPUT");
    assertThat(json(res).path("error").path("field").asText()).isEqualTo("gpa");
  }

  @Test
  @DisplayName("희망 직무 경계 — 4개는 422, 알 수 없는 코드는 400, 중복 priority 는 400")
  void jobInterestBounds() {
    Session session = login();

    ResponseEntity<String> four =
        call(
            HttpMethod.PUT,
            "/users/me/job-interests",
            Map.of(
                "interests",
                List.of(
                    Map.of("code", "backend", "priority", 1),
                    Map.of("code", "frontend", "priority", 2),
                    Map.of("code", "mobile", "priority", 3),
                    Map.of("code", "data", "priority", 3))),
            session.accessToken());
    assertThat(four.getStatusCode().value()).isEqualTo(422);
    assertThat(json(four).path("error").path("code").asText()).isEqualTo("LIMIT_EXCEEDED");

    ResponseEntity<String> unknown =
        call(
            HttpMethod.PUT,
            "/users/me/job-interests",
            Map.of("interests", List.of(Map.of("code", "astronaut", "priority", 1))),
            session.accessToken());
    assertThat(unknown.getStatusCode().value()).isEqualTo(400);

    ResponseEntity<String> dupPriority =
        call(
            HttpMethod.PUT,
            "/users/me/job-interests",
            Map.of(
                "interests",
                List.of(
                    Map.of("code", "backend", "priority", 1),
                    Map.of("code", "frontend", "priority", 1))),
            session.accessToken());
    assertThat(dupPriority.getStatusCode().value()).isEqualTo(400);
  }

  @Test
  @DisplayName("태그 — 6개는 422, 빈 태그는 400, 공백 정규화 후 중복은 조용히 제거")
  void tagRules() {
    Session session = login();

    ResponseEntity<String> six =
        call(
            HttpMethod.PUT,
            "/users/me/tags",
            Map.of("tags", List.of("a", "b", "c", "d", "e", "f")),
            session.accessToken());
    assertThat(six.getStatusCode().value()).isEqualTo(422);

    ResponseEntity<String> blank =
        call(
            HttpMethod.PUT,
            "/users/me/tags",
            Map.of("tags", List.of("AI", "  ")),
            session.accessToken());
    assertThat(blank.getStatusCode().value()).isEqualTo(400);

    ResponseEntity<String> deduped =
        call(
            HttpMethod.PUT,
            "/users/me/tags",
            Map.of("tags", List.of("  AI ", "AI", "스타트업")),
            session.accessToken());
    assertThat(json(deduped).path("data").path("tags"))
        .extracting(JsonNode::asText)
        .containsExactly("AI", "스타트업");
  }

  @Test
  @DisplayName("메타 목록 — 학교 검색과 직무 목록이 캐시 헤더와 함께 내려온다 (#17)")
  void metaEndpoints() {
    Session session = login();

    ResponseEntity<String> schools =
        call(HttpMethod.GET, "/meta/schools?q=건국", null, session.accessToken());
    assertThat(schools.getStatusCode().value()).isEqualTo(200);
    assertThat(json(schools).path("data").path("schools").toString()).contains("건국대학교");
    assertThat(schools.getHeaders().getCacheControl()).contains("max-age");

    ResponseEntity<String> categories =
        call(HttpMethod.GET, "/meta/job-categories", null, session.accessToken());
    assertThat(json(categories).path("data").path("categories").toString()).contains("backend");
  }
}
