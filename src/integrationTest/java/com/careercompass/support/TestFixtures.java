package com.careercompass.support;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** 통합 테스트 데이터 픽스처 — 사용자·게시판·공고·경험 카드 (issue #9). 도메인 이슈가 진행되며 확장한다. */
public final class TestFixtures {

  private TestFixtures() {}

  public static long user(JdbcTemplate jdbc) {
    String providerId = UUID.randomUUID().toString();
    Long id =
        jdbc.queryForObject(
            "insert into users (provider, provider_id, email, name)"
                + " values ('kakao', ?, ?, '테스트사용자') returning id",
            Long.class,
            providerId,
            providerId + "@test.local");
    jdbc.update(
        "insert into user_profile (user_id, school, department, onboarding_done)"
            + " values (?, '건국대학교', '컴퓨터공학부', true)",
        id);
    return id;
  }

  public static long board(JdbcTemplate jdbc, long userId, String url) {
    return jdbc.queryForObject(
        "insert into board (user_id, url, name, type)"
            + " values (?, ?, '테스트 게시판', 'mixed') returning id",
        Long.class,
        userId,
        url);
  }

  public static long posting(JdbcTemplate jdbc, long boardId, String title) {
    return jdbc.queryForObject(
        "insert into posting (board_id, title, url, url_hash, type)"
            + " values (?, ?, ?, ?, 'recruit') returning id",
        Long.class,
        boardId,
        title,
        "https://test.local/" + UUID.randomUUID(),
        UUID.randomUUID().toString());
  }

  public static long pastApplication(JdbcTemplate jdbc, long userId, String label) {
    return jdbc.queryForObject(
        "insert into past_application (user_id, label, file_path, file_type, file_size_bytes,"
            + " extract_status) values (?, ?, ?, 'txt', 100, 'done') returning id",
        Long.class,
        userId,
        label,
        "local://past-applications/" + userId + "/" + UUID.randomUUID() + ".txt");
  }

  public static long experienceCard(JdbcTemplate jdbc, long userId, String type, String title) {
    return jdbc.queryForObject(
        "insert into experience_card (user_id, type, title, data)"
            + " values (?, ?, ?, '{}'::jsonb) returning id",
        Long.class,
        userId,
        type,
        title);
  }
}
