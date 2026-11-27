package com.careercompass.user.application;

import com.careercompass.common.storage.FileStorage;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 계정 삭제 (#61) — 즉시·전부 삭제한다. 유예 기간을 두지 않고, 분석·모델 개선용으로 익명화해 남기는 데이터도 없다(#48: 동의 기록까지 함께 지운다).
 *
 * <p>순서가 중요하다. application 은 posting 을 cascade 없이 참조하므로 게시판(→공고 cascade)보다 먼저 지운다. 파일 저장소의 원본은 DB
 * 커밋이 끝난 뒤 지운다 — 커밋이 실패했는데 파일만 사라지는 일을 막는다.
 *
 * <p>사용자를 참조하는 테이블이 늘면 이 목록도 늘려야 한다. 통합 테스트가 information_schema 의 모든 user_id 컬럼을 훑어 빠진 테이블을 잡는다.
 */
@Service
public class AccountDeletionService {

  private static final Logger log = LoggerFactory.getLogger(AccountDeletionService.class);

  /** user_id 로 지우는 테이블 — 앞에서부터 차례로. 하위 행(항목·버전·공고·파싱 결과)은 DB on delete cascade 가 맡는다. */
  static final List<String> USER_TABLES =
      List.of(
          "application", // → application_item → application_item_version
          "suitability_score",
          "posting_user_state",
          "board", // → posting → posting_parsed, posting_user_state, suitability_score
          "past_application", // → past_application_item
          "experience_card",
          "user_tag",
          "user_job_interest",
          "user_profile",
          "user_consent",
          "notification",
          "notification_setting",
          "user_device", // FCM 토큰·지문 등록
          "refresh_token");

  private final JdbcTemplate jdbc;
  private final FileStorage fileStorage;

  public AccountDeletionService(JdbcTemplate jdbc, FileStorage fileStorage) {
    this.jdbc = jdbc;
    this.fileStorage = fileStorage;
  }

  @Transactional
  public void delete(Long userId) {
    List<String> fileUris =
        jdbc.queryForList(
            "select file_path from past_application where user_id = ?", String.class, userId);

    for (String table : USER_TABLES) {
      jdbc.update("delete from " + table + " where user_id = ?", userId);
    }
    jdbc.update("delete from users where id = ?", userId); // 소셜 연결(provider, provider_id)도 여기서 끊긴다

    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            for (String uri : fileUris) {
              deleteQuietly(uri);
            }
          }
        });
    log.info("account-deleted userId={} files={}", userId, fileUris.size());
  }

  private void deleteQuietly(String uri) {
    int schemeEnd = uri.indexOf("://");
    String key = schemeEnd >= 0 ? uri.substring(schemeEnd + 3) : uri;
    try {
      fileStorage.delete(key);
    } catch (RuntimeException e) {
      // DB 는 이미 지워졌다 — 남은 파일은 로그로 남겨 수동 정리한다
      log.warn("계정 삭제 후 파일 정리 실패: {} ({})", key, e.toString());
    }
  }
}
