package com.careercompass.extended;

import com.careercompass.common.api.ErrorCode;
import com.careercompass.common.exception.BusinessException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 커리어 로드맵 코호트 비교 (#42). 지표: 경험 카드 유형별 개수(프로젝트·인턴·수상·자격증) + 태그 수. 최소 표본 5명 미만이면 metrics 를 비워 개인 식별을
 * 막는다. senior 코호트는 pass 결과 + 모델 개선 동의 사용자만 집계한다(#48). suggestions 는 규칙 기반(결정) — 가장 뒤처진 지표를 메우는 행동
 * 제안.
 */
@Service
public class RoadmapService {

  public static final int MIN_SAMPLE = 5;

  /** peerAvg 는 늘 숫자다(FE 는 null 을 받지 않는다). me_only 는 비교 대상이 없어 me 와 같은 값을 싣는다. */
  public record Metric(String name, double me, double peerAvg) {}

  public record Suggestion(String semester, String action, int expectedLift) {}

  public record Compare(
      String cohort, int sampleSize, List<Metric> metrics, List<Suggestion> suggestions) {}

  private static final Map<String, String> METRIC_LABELS =
      new LinkedHashMap<>(
          Map.of(
              "project", "프로젝트 수",
              "intern", "인턴 경험",
              "award", "수상 이력",
              "cert", "자격증"));

  private static final Map<String, String> GAP_ACTIONS =
      Map.of(
          "project", "사이드 프로젝트 1개 완성하기",
          "intern", "채용 연계형 인턴 지원하기",
          "award", "교내·외 공모전 1회 도전하기",
          "cert", "직무 관련 자격증(SQLD·정보처리기사 등) 취득하기");

  private final NamedParameterJdbcTemplate jdbc;

  public RoadmapService(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(readOnly = true)
  public Compare compare(Long userId, String cohort) {
    Map<String, Double> mine = metricsOf(List.of(userId));
    switch (cohort) {
      case "me_only" -> {
        List<Metric> metrics =
            METRIC_LABELS.entrySet().stream()
                .map(
                    e -> {
                      double value = mine.getOrDefault(e.getKey(), 0d);
                      return new Metric(e.getValue(), value, value); // 비교 대상 없음 — FE 는 me 만 그린다
                    })
                .toList();
        return new Compare(cohort, 1, metrics, suggestForMeOnly(mine));
      }
      case "peer" -> {
        List<Long> peers =
            jdbc.queryForList(
                """
                select p2.user_id from user_profile p2
                join user_profile me on me.user_id = :userId
                where p2.user_id <> :userId
                  and p2.department = me.department
                  and p2.grad_year = me.grad_year
                """,
                Map.of("userId", userId),
                Long.class);
        return buildCompare(cohort, mine, peers);
      }
      case "senior" -> {
        List<Long> seniors =
            jdbc.queryForList(
                """
                select distinct a.user_id from application a
                join user_consent c on c.user_id = a.user_id and c.model_improvement
                where a.result = 'pass' and a.user_id <> :userId
                """,
                Map.of("userId", userId),
                Long.class);
        return buildCompare(cohort, mine, seniors);
      }
      default ->
          throw new BusinessException(
              ErrorCode.INVALID_INPUT, "cohort 는 peer/senior/me_only 중 하나여야 합니다", "cohort");
    }
  }

  private Compare buildCompare(String cohort, Map<String, Double> mine, List<Long> cohortIds) {
    if (cohortIds.size() < MIN_SAMPLE) {
      // 표본이 적으면 개인이 식별된다 — 비교를 주지 않는다 (#42)
      return new Compare(cohort, cohortIds.size(), List.of(), List.of());
    }
    Map<String, Double> avg = metricsOf(cohortIds);
    List<Metric> metrics =
        METRIC_LABELS.entrySet().stream()
            .map(
                e ->
                    new Metric(
                        e.getValue(),
                        mine.getOrDefault(e.getKey(), 0d),
                        avg.getOrDefault(e.getKey(), 0d)))
            .toList();
    return new Compare(cohort, cohortIds.size(), metrics, suggestions(mine, avg));
  }

  /** 코호트 평균 지표 — 유형별 카드 수 / 인원. */
  private Map<String, Double> metricsOf(List<Long> userIds) {
    if (userIds.isEmpty()) {
      return Map.of();
    }
    Map<String, Double> metrics = new LinkedHashMap<>();
    jdbc.query(
        """
        select type, count(*) as cnt from experience_card
        where user_id in (:userIds) and deleted_at is null
        group by type
        """,
        Map.of("userIds", userIds),
        rs -> {
          metrics.put(
              rs.getString("type"), Math.round(rs.getLong("cnt") * 10.0 / userIds.size()) / 10.0);
        });
    return metrics;
  }

  private List<Suggestion> suggestions(Map<String, Double> mine, Map<String, Double> avg) {
    List<Suggestion> suggestions = new ArrayList<>();
    String worst = null;
    double worstGap = 0;
    for (String key : METRIC_LABELS.keySet()) {
      double gap = avg.getOrDefault(key, 0d) - mine.getOrDefault(key, 0d);
      if (gap > worstGap) {
        worstGap = gap;
        worst = key;
      }
    }
    if (worst != null) {
      suggestions.add(
          new Suggestion(
              "다음 학기",
              GAP_ACTIONS.get(worst),
              (int) Math.min(20, Math.max(5, Math.round(worstGap * 10)))));
    }
    return suggestions;
  }

  private List<Suggestion> suggestForMeOnly(Map<String, Double> mine) {
    List<Suggestion> suggestions = new ArrayList<>();
    for (String key : METRIC_LABELS.keySet()) {
      if (mine.getOrDefault(key, 0d) == 0d && suggestions.size() < 2) {
        suggestions.add(new Suggestion("다음 학기", GAP_ACTIONS.get(key), 10));
      }
    }
    return suggestions;
  }
}
