package com.careercompass.extended;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * For You 추천 (#41) — 적합도 산출(#32) 위에 선다: 점수 없는 공고는 대상에서 뺀다. 사용자당 공고 수가 작아 매 요청 계산한다(배치 불필요, 결정).
 *
 * <ul>
 *   <li>톱 픽: 적합도 + 마감 임박 가중(D-7 이내 +5)
 *   <li>강점 기반: preference 축 ≥ 50 — 경험 카드가 우대 조건과 실제로 맞는 공고
 *   <li>취약점 보완: field ≥ 50 인데 preference &lt; 50 — 관심 분야는 맞지만 경험이 부족한 공고(경험을 쌓을 기회)
 * </ul>
 */
@Service
public class ForYouService {

  public record Pick(Long postingId, String title, int score, String reason) {}

  public record TopPick(Long postingId, String title, int score, List<String> reason) {}

  public record ForYou(TopPick topPick, List<Pick> byStrength, List<Pick> byGap) {}

  private record ScoredPosting(
      long postingId, String title, LocalDate dueDate, int score, int fieldAxis, int prefAxis) {}

  private final NamedParameterJdbcTemplate jdbc;
  private final Clock clock;

  public ForYouService(NamedParameterJdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public ForYou recommend(Long userId) {
    LocalDate today = LocalDate.now(clock);
    List<ScoredPosting> scored =
        jdbc.query(
            """
            select p.id, p.title, p.due_date, s.score, s.breakdown
            from suitability_score s
            join posting p on p.id = s.posting_id
            join board b on b.id = p.board_id and b.user_id = :userId
            where s.user_id = :userId and (p.due_date is null or p.due_date >= :today)
            order by s.score desc, p.id desc
            limit 100
            """,
            Map.of("userId", userId, "today", today),
            (rs, rowNum) -> {
              int field = 0;
              int pref = 0;
              try {
                var breakdown =
                    new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(rs.getString("breakdown"));
                for (var axis : breakdown) {
                  if ("field_similarity".equals(axis.path("axis").asText())) {
                    field = axis.path("score").asInt();
                  }
                  if ("preference".equals(axis.path("axis").asText())) {
                    pref = axis.path("score").asInt();
                  }
                }
              } catch (Exception ignored) {
                // breakdown 파싱 실패 시 축 0 으로 취급
              }
              return new ScoredPosting(
                  rs.getLong("id"),
                  rs.getString("title"),
                  rs.getObject("due_date", LocalDate.class),
                  rs.getInt("score"),
                  field,
                  pref);
            });

    if (scored.isEmpty()) {
      return new ForYou(null, List.of(), List.of());
    }

    ScoredPosting top =
        scored.stream()
            .max(Comparator.comparingInt(p -> p.score() + urgencyBonus(p.dueDate(), today)))
            .orElseThrow();
    List<String> topReasons = new ArrayList<>();
    topReasons.add("적합도 " + top.score() + "점으로 가장 높습니다");
    if (top.dueDate() != null) {
      long dday = ChronoUnit.DAYS.between(today, top.dueDate());
      if (dday <= 7) {
        topReasons.add("마감 D-" + dday + " — 서두르세요");
      }
    }

    List<Pick> byStrength =
        scored.stream()
            .filter(p -> p.postingId() != top.postingId() && p.prefAxis() >= 50)
            .limit(5)
            .map(
                p ->
                    new Pick(
                        p.postingId(),
                        p.title(),
                        p.score(),
                        "경험 카드가 우대 조건과 일치합니다 (우대 축 " + p.prefAxis() + "점)"))
            .toList();

    List<Pick> byGap =
        scored.stream()
            .filter(
                p -> p.postingId() != top.postingId() && p.fieldAxis() >= 50 && p.prefAxis() < 50)
            .limit(5)
            .map(
                p ->
                    new Pick(
                        p.postingId(),
                        p.title(),
                        p.score(),
                        "관심 분야와 맞지만 관련 경험이 부족합니다 — 경험을 보완할 기회입니다"))
            .toList();

    return new ForYou(
        new TopPick(top.postingId(), top.title(), top.score(), topReasons), byStrength, byGap);
  }

  private int urgencyBonus(LocalDate dueDate, LocalDate today) {
    if (dueDate == null) {
      return 0;
    }
    long days = ChronoUnit.DAYS.between(today, dueDate);
    return days >= 0 && days <= 7 ? 5 : 0;
  }
}
