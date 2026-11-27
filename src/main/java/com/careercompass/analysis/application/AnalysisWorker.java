package com.careercompass.analysis.application;

import com.careercompass.board.crawl.CrawlEvents;
import com.careercompass.common.api.ErrorCode;
import com.careercompass.common.events.ProfileChanged;
import com.careercompass.common.exception.BusinessException;
import com.careercompass.posting.domain.PostingParsed;
import com.careercompass.posting.domain.PostingParsedRepository;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 분석 비동기 워커 (#31, #32) — 수집 직후 파싱·점수 산출을 트리거하고, 스윕이 남은 pending·미산출 쌍을 채운다. LLM 장애 시 pending 으로 남아
 * 다음 스윕에서 재시도된다. 테스트에서는 property 로 끈다.
 */
@Component
@ConditionalOnProperty(
    name = "app.analysis.worker-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class AnalysisWorker {

  private static final Logger log = LoggerFactory.getLogger(AnalysisWorker.class);

  private final AnalysisService analysisService;
  private final PostingParsedRepository parsedRepository;
  private final NamedParameterJdbcTemplate jdbc;

  public AnalysisWorker(
      AnalysisService analysisService,
      PostingParsedRepository parsedRepository,
      NamedParameterJdbcTemplate jdbc) {
    this.analysisService = analysisService;
    this.parsedRepository = parsedRepository;
    this.jdbc = jdbc;
  }

  @Async
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onNewPostings(CrawlEvents.NewPostingsCollected event) {
    for (Long postingId : event.postingIds()) {
      try {
        if (analysisService.parsePosting(postingId)) {
          computeQuietly(event.userId(), postingId);
        }
      } catch (Exception e) {
        log.warn("파싱 워커 오류: posting={} ({})", postingId, e.toString());
      }
    }
  }

  @Async
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onProfileChanged(ProfileChanged event) {
    analysisService.invalidateScores(event.userId());
    // 재계산은 스윕이 이어받는다 (#32 — 사전 계산 + 변경 시 무효화)
  }

  /** 스윕 — pending 파싱과 미산출 (user, posting) 쌍을 배치로 처리한다. */
  @Scheduled(fixedDelayString = "${app.analysis.sweep-interval-ms:60000}")
  public void sweep() {
    for (PostingParsed pending :
        parsedRepository.findTop20ByParseStatusOrderByPostingId("pending")) {
      try {
        analysisService.parsePosting(pending.postingId());
      } catch (Exception e) {
        log.warn("스윕 파싱 오류: posting={} ({})", pending.postingId(), e.toString());
      }
    }

    List<Map<String, Object>> missing =
        jdbc.queryForList(
            """
            select b.user_id as user_id, p.id as posting_id
            from posting p
            join board b on b.id = p.board_id
            join posting_parsed pp on pp.posting_id = p.id and pp.parse_status = 'done'
            left join suitability_score s on s.posting_id = p.id and s.user_id = b.user_id
            where s.id is null
            limit 50
            """,
            Map.of());
    for (Map<String, Object> pair : missing) {
      computeQuietly(
          ((Number) pair.get("user_id")).longValue(),
          ((Number) pair.get("posting_id")).longValue());
    }
  }

  private void computeQuietly(Long userId, Long postingId) {
    try {
      analysisService.computeScore(userId, postingId);
    } catch (BusinessException e) {
      if (e.code() != ErrorCode.PROFILE_INCOMPLETE && e.code() != ErrorCode.PARSING_FAILED) {
        log.warn("점수 산출 오류: user={} posting={} ({})", userId, postingId, e.getMessage());
      }
    } catch (Exception e) {
      log.warn("점수 산출 오류: user={} posting={}", userId, postingId, e);
    }
  }
}
