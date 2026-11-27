package com.careercompass.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 통합 테스트 기반 — 실제 PostgreSQL 16 컨테이너 위에서 Flyway 마이그레이션·JPA validate 까지 태운다 (issue #9).
 *
 * <p>컨테이너는 JVM 당 1회 기동해 모든 통합 테스트가 공유한다. 외부 의존(LLM·크롤링 대상·FCM)은 각 테스트에서 fake/stub 으로 대체한다 — 실제 호출
 * 금지.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTestBase {

  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("auth.jwt.secret", () -> "integration-test-secret-key-0123456789abcdef");
    registry.add("app.storage.local-dir", () -> "build/it-storage");
    // 크롤러: 픽스처 서버(localhost) 허용, 스케줄러 정지, 빠른 타임아웃
    registry.add("app.crawl.allow-private-addresses", () -> "true");
    registry.add("app.crawl.scheduler-enabled", () -> "false");
    registry.add("app.analysis.worker-enabled", () -> "false");
    registry.add("app.notification.worker-enabled", () -> "false");
    registry.add("app.crawl.min-domain-interval-ms", () -> "10");
    registry.add("app.crawl.connect-timeout", () -> "2s");
    registry.add("app.crawl.read-timeout", () -> "3s");
  }
}
