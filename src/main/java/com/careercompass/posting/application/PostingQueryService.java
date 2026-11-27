package com.careercompass.posting.application;

import com.careercompass.analysis.domain.SuitabilityScore;
import com.careercompass.analysis.domain.SuitabilityScoreRepository;
import com.careercompass.board.domain.BoardRepository;
import com.careercompass.common.api.ErrorCode;
import com.careercompass.common.exception.BusinessException;
import com.careercompass.common.paging.Cursor;
import com.careercompass.common.paging.CursorCodec;
import com.careercompass.common.paging.CursorPage;
import com.careercompass.posting.domain.Posting;
import com.careercompass.posting.domain.PostingParsed;
import com.careercompass.posting.domain.PostingParsedRepository;
import com.careercompass.posting.domain.PostingRepository;
import com.careercompass.posting.domain.PostingUserState;
import com.careercompass.posting.domain.PostingUserStateRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공고 목록·상세·상태 (#27~#30). 목록 계약: 마감 공고 기본 숨김, due_asc 는 마감일 있는 공고만·score_desc 는 점수 산출된 공고만 (정렬 축과 커서
 * 일관성). 카드에 parseStatus(pending|done|failed)를 실어 FE 가 「분석 중/실패」를 가른다 (#27).
 */
@Service
public class PostingQueryService {

  private static final Set<String> SORTS = Set.of("collected_desc", "due_asc", "score_desc");
  private static final Set<String> TYPES =
      Set.of("scholarship", "contest", "recruit", "activity", "other");
  private static final Set<String> APPLICATION_TYPES = Set.of("scholarship", "recruit");

  /** 「오늘」을 서버가 판정하는 유일한 자리(범위 없는 마감 숨김)의 시간대 (#56). */
  static final java.time.ZoneId SEOUL = java.time.ZoneId.of("Asia/Seoul");

  /**
   * 목록 필터 (#28 #56). dueFrom·dueTo 는 그날 포함, 한쪽만 와도 된다. includeNoDueDate 가 null 이면 true(마감일 없는 공고
   * 포함).
   */
  public record ListFilter(
      List<Long> boardIds,
      List<String> types,
      Integer minScore,
      boolean unreadOnly,
      boolean bookmarkedOnly,
      String q,
      LocalDate dueFrom,
      LocalDate dueTo,
      Boolean includeNoDueDate,
      boolean includeExpired) {}

  public record PostingCard(
      Long id,
      String title,
      String type,
      Long boardId,
      String boardName,
      LocalDate dueDate,
      Instant collectedAt,
      String parseStatus,
      Integer score,
      String scoreLabel,
      String scoreUnavailableReason, // #34: profile_incomplete | parsing_failed | analyzing | null
      boolean isRead,
      boolean isBookmarked) {}

  private final NamedParameterJdbcTemplate jdbc;
  private final PostingRepository postingRepository;
  private final PostingParsedRepository postingParsedRepository;
  private final PostingUserStateRepository stateRepository;
  private final SuitabilityScoreRepository scoreRepository;
  private final BoardRepository boardRepository;
  private final Clock clock;

  public PostingQueryService(
      NamedParameterJdbcTemplate jdbc,
      PostingRepository postingRepository,
      PostingParsedRepository postingParsedRepository,
      PostingUserStateRepository stateRepository,
      SuitabilityScoreRepository scoreRepository,
      BoardRepository boardRepository,
      Clock clock) {
    this.jdbc = jdbc;
    this.postingRepository = postingRepository;
    this.postingParsedRepository = postingParsedRepository;
    this.stateRepository = stateRepository;
    this.scoreRepository = scoreRepository;
    this.boardRepository = boardRepository;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public CursorPage<PostingCard> list(
      Long userId, ListFilter filter, String sort, String cursor, int limit) {
    if (!SORTS.contains(sort)) {
      throw new BusinessException(
          ErrorCode.INVALID_INPUT, "sort 는 collected_desc/due_asc/score_desc 중 하나여야 합니다", "sort");
    }
    if (filter.types() != null) {
      for (String type : filter.types()) {
        if (!TYPES.contains(type)) {
          throw new BusinessException(ErrorCode.INVALID_INPUT, "알 수 없는 공고 유형입니다: " + type, "types");
        }
      }
    }

    Map<String, Object> params = new HashMap<>();
    params.put("userId", userId);
    StringBuilder sql =
        new StringBuilder(
            """
            select p.id, p.title, p.type, p.due_date, p.collected_at, p.board_id,
                   b.name as board_name, coalesce(pp.parse_status, 'pending') as parse_status,
                   s.score, s.label,
                   coalesce(st.is_read, false) as is_read,
                   coalesce(st.is_bookmarked, false) as is_bookmarked
            from posting p
            join board b on b.id = p.board_id
            left join posting_parsed pp on pp.posting_id = p.id
            left join suitability_score s on s.posting_id = p.id and s.user_id = :userId
            left join posting_user_state st on st.posting_id = p.id and st.user_id = :userId
            where b.user_id = :userId
              and coalesce(pp.parse_status, 'pending') <> 'excluded'
            """);

    if (filter.boardIds() != null && !filter.boardIds().isEmpty()) {
      sql.append(" and p.board_id in (:boardIds)");
      params.put("boardIds", filter.boardIds());
    }
    if (filter.types() != null && !filter.types().isEmpty()) {
      sql.append(" and p.type in (:types)");
      params.put("types", filter.types());
    }
    if (filter.minScore() != null) {
      sql.append(" and s.score >= :minScore");
      params.put("minScore", filter.minScore());
    }
    if (filter.unreadOnly()) {
      sql.append(" and coalesce(st.is_read, false) = false");
    }
    if (filter.bookmarkedOnly()) {
      sql.append(" and coalesce(st.is_bookmarked, false) = true");
    }
    if (filter.q() != null && !filter.q().isBlank()) {
      // #56: 제목과 parsed.keywords 의 부분 문자열(대소문자 무시). 원문(rawContent)은 뒤지지 않는다.
      sql.append(
          " and (p.title ilike :q escape '\\' or exists (select 1 from"
              + " jsonb_array_elements_text(coalesce(pp.keywords, '[]'::jsonb)) k(kw)"
              + " where k.kw ilike :q escape '\\'))");
      params.put("q", "%" + escapeLike(filter.q().trim()) + "%");
    }
    appendDueConditions(sql, params, filter);

    Cursor decoded = cursor == null ? null : CursorCodec.decode(cursor, sort);
    switch (sort) {
      case "collected_desc" -> {
        if (decoded != null) {
          sql.append(" and (p.collected_at < :cKey or (p.collected_at = :cKey and p.id < :cId))");
          params.put(
              "cKey", java.sql.Timestamp.from(Instant.parse((String) decoded.keys().get(0))));
          params.put("cId", decoded.id());
        }
        sql.append(" order by p.collected_at desc, p.id desc");
      }
      case "due_asc" -> {
        sql.append(" and p.due_date is not null");
        if (decoded != null) {
          sql.append(" and (p.due_date > :cKey or (p.due_date = :cKey and p.id > :cId))");
          params.put("cKey", LocalDate.parse((String) decoded.keys().get(0)));
          params.put("cId", decoded.id());
        }
        sql.append(" order by p.due_date asc, p.id asc");
      }
      case "score_desc" -> {
        sql.append(" and s.score is not null");
        if (decoded != null) {
          sql.append(" and (s.score < :cKey or (s.score = :cKey and p.id < :cId))");
          params.put("cKey", ((Number) decoded.keys().get(0)).intValue());
          params.put("cId", decoded.id());
        }
        sql.append(" order by s.score desc, p.id desc");
      }
      default -> throw new IllegalStateException();
    }
    sql.append(" limit :limitPlusOne");
    params.put("limitPlusOne", limit + 1);

    List<PostingCard> fetched =
        withUnavailableReasons(userId, jdbc.query(sql.toString(), params, CARD_MAPPER));
    return CursorPage.of(fetched, limit, card -> cursorOf(sort, card));
  }

  private static final org.springframework.jdbc.core.RowMapper<PostingCard> CARD_MAPPER =
      (rs, rowNum) ->
          new PostingCard(
              rs.getLong("id"),
              rs.getString("title"),
              rs.getString("type"),
              rs.getLong("board_id"),
              rs.getString("board_name"),
              rs.getObject("due_date", LocalDate.class),
              rs.getTimestamp("collected_at").toInstant(),
              rs.getString("parse_status"),
              rs.getObject("score") == null ? null : rs.getInt("score"),
              rs.getString("label"),
              null,
              rs.getBoolean("is_read"),
              rs.getBoolean("is_bookmarked"));

  private List<PostingCard> withUnavailableReasons(Long userId, List<PostingCard> cards) {
    if (cards.stream().noneMatch(card -> card.score() == null)) {
      return cards;
    }
    boolean profileIncomplete = profileIncomplete(userId);
    return cards.stream()
        .map(
            card ->
                new PostingCard(
                    card.id(),
                    card.title(),
                    card.type(),
                    card.boardId(),
                    card.boardName(),
                    card.dueDate(),
                    card.collectedAt(),
                    card.parseStatus(),
                    card.score(),
                    card.scoreLabel(),
                    unavailableReason(card.score(), card.parseStatus(), profileIncomplete),
                    card.isRead(),
                    card.isBookmarked()))
        .toList();
  }

  /**
   * 마감일 조건 (#56). 범위(dueFrom·dueTo)가 하나라도 오면 그 범위만 보고 「마감 지남 숨김」을 얹지 않는다. 범위가 없을 때만 오늘(KST) 이전 마감을
   * 숨기고 includeExpired 로 끈다. 마감일 없는 공고는 includeNoDueDate(기본 true)로 따로 가른다 — 날짜 조건은 null 행을 조용히 뺀다.
   */
  private void appendDueConditions(
      StringBuilder sql, Map<String, Object> params, ListFilter filter) {
    boolean includeNoDue = filter.includeNoDueDate() == null || filter.includeNoDueDate();
    boolean ranged = filter.dueFrom() != null || filter.dueTo() != null;
    List<String> bounds = new java.util.ArrayList<>();
    if (ranged) {
      if (filter.dueFrom() != null) {
        bounds.add("p.due_date >= :dueFrom");
        params.put("dueFrom", filter.dueFrom());
      }
      if (filter.dueTo() != null) {
        bounds.add("p.due_date <= :dueTo");
        params.put("dueTo", filter.dueTo());
      }
    } else if (!filter.includeExpired()) {
      bounds.add("p.due_date >= :today");
      params.put("today", todayInSeoul());
    }
    String dated = bounds.isEmpty() ? "p.due_date is not null" : String.join(" and ", bounds);
    if (includeNoDue) {
      sql.append(" and (p.due_date is null or (").append(dated).append("))");
    } else {
      sql.append(" and p.due_date is not null and ").append(dated);
    }
  }

  private LocalDate todayInSeoul() {
    return LocalDate.now(clock.withZone(SEOUL));
  }

  private static String escapeLike(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  /**
   * #34 계약 — 목록·상세가 같은 이름을 쓴다. 우선순위: 사용자가 고칠 수 있는 사유(profile_incomplete) → 영구 실패(parsing_failed) →
   * 대기 (analyzing).
   */
  private String unavailableReason(Integer score, String parseStatus, boolean profileIncomplete) {
    if (score != null) {
      return null;
    }
    if (profileIncomplete) {
      return "profile_incomplete";
    }
    if ("failed".equals(parseStatus)) {
      return "parsing_failed";
    }
    return "analyzing";
  }

  /** 경험 카드 0개 + 관심 분야(직무·태그) 미입력 → 산출 불가 (명세서 F3-2 예외). */
  private boolean profileIncomplete(Long userId) {
    Boolean incomplete =
        jdbc.queryForObject(
            """
            select not exists(select 1 from user_job_interest where user_id = :userId)
               and not exists(select 1 from user_tag where user_id = :userId)
               and not exists(select 1 from experience_card
                              where user_id = :userId and deleted_at is null)
            """,
            Map.of("userId", userId),
            Boolean.class);
    return Boolean.TRUE.equals(incomplete);
  }

  private Cursor cursorOf(String sort, PostingCard card) {
    return switch (sort) {
      case "collected_desc" -> new Cursor(sort, List.of(card.collectedAt().toString()), card.id());
      case "due_asc" -> new Cursor(sort, List.of(card.dueDate().toString()), card.id());
      case "score_desc" -> new Cursor(sort, List.of(card.score()), card.id());
      default -> throw new IllegalStateException(sort);
    };
  }

  // ─── 상세 (#29) ───────────────────────────────────────────────

  public record Detail(
      Posting posting,
      String boardName,
      String parseStatus,
      PostingParsed parsed,
      SuitabilityScore suitability,
      String scoreUnavailableReason,
      boolean isRead,
      boolean isBookmarked,
      boolean canGenerateApplication,
      List<PostingCard> similar) {} // 목록 카드와 같은 모양 (FE 는 similar 를 목록 항목 스키마로 읽는다)

  @Transactional(readOnly = true)
  public Detail detail(Long userId, Long postingId) {
    Posting posting = owned(userId, postingId);
    Optional<PostingParsed> parsed = postingParsedRepository.findById(postingId);
    String parseStatus = parsed.map(PostingParsed::parseStatus).orElse("pending");
    SuitabilityScore suitability =
        scoreRepository.findByUserIdAndPostingId(userId, postingId).orElse(null);
    Optional<PostingUserState> state = stateRepository.findByUserIdAndPostingId(userId, postingId);

    return new Detail(
        posting,
        boardRepository.findById(posting.boardId()).map(b -> b.name()).orElse(""),
        parseStatus,
        parsed.filter(p -> "done".equals(p.parseStatus())).orElse(null),
        suitability,
        unavailableReason(
            suitability == null ? null : suitability.score(),
            parseStatus,
            suitability == null && profileIncomplete(userId)),
        state.map(PostingUserState::isRead).orElse(false),
        state.map(PostingUserState::isBookmarked).orElse(false),
        // 지원서 작성 버튼은 장학금·채용만 — 판단은 서버가 내려준다 (#29 결정)
        APPLICATION_TYPES.contains(posting.type()),
        similar(userId, posting, parsed.map(PostingParsed::keywords).orElse(null)));
  }

  /** 유사 공고 3건 — 동일 유형 후보 20건에서 키워드 겹침 → 적합도 → 최신 순으로 고른다 (F3-3). */
  private List<PostingCard> similar(Long userId, Posting posting, List<String> keywords) {
    Map<String, Object> params = new HashMap<>();
    params.put("userId", userId);
    params.put("type", posting.type());
    params.put("selfId", posting.id());
    params.put("today", todayInSeoul());
    record Candidate(PostingCard card, List<String> kw) {}
    List<Candidate> candidates =
        jdbc.query(
            """
            select p.id, p.title, p.type, p.due_date, p.collected_at, p.board_id,
                   b.name as board_name, coalesce(pp.parse_status, 'pending') as parse_status,
                   s.score, s.label,
                   coalesce(st.is_read, false) as is_read,
                   coalesce(st.is_bookmarked, false) as is_bookmarked,
                   pp.keywords
            from posting p
            join board b on b.id = p.board_id
            left join suitability_score s on s.posting_id = p.id and s.user_id = :userId
            left join posting_parsed pp on pp.posting_id = p.id
            left join posting_user_state st on st.posting_id = p.id and st.user_id = :userId
            where b.user_id = :userId and p.type = :type and p.id <> :selfId
              and (p.due_date is null or p.due_date >= :today)
              and coalesce(pp.parse_status, 'pending') <> 'excluded'
            order by s.score desc nulls last, p.collected_at desc
            limit 20
            """,
            params,
            (rs, rowNum) ->
                new Candidate(
                    CARD_MAPPER.mapRow(rs, rowNum), parseJsonArray(rs.getString("keywords"))));

    Set<String> baseKeywords = keywords == null ? Set.of() : new HashSet<>(keywords);
    List<PostingCard> top =
        candidates.stream()
            .sorted(
                Comparator.<Candidate>comparingLong(c -> -overlap(baseKeywords, c.kw()))
                    .thenComparing(
                        c -> c.card().score() == null ? -1 : c.card().score(),
                        Comparator.reverseOrder()))
            .limit(3)
            .map(Candidate::card)
            .toList();
    return withUnavailableReasons(userId, top);
  }

  private long overlap(Set<String> base, List<String> other) {
    if (base.isEmpty() || other == null) {
      return 0;
    }
    return other.stream().filter(base::contains).count();
  }

  private List<String> parseJsonArray(String json) {
    if (json == null) {
      return null;
    }
    try {
      return new com.fasterxml.jackson.databind.ObjectMapper()
          .readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
    } catch (Exception e) {
      return null;
    }
  }

  // ─── 상태 (#30) ───────────────────────────────────────────────

  @Transactional
  public void markRead(Long userId, Long postingId) {
    owned(userId, postingId);
    state(userId, postingId).markRead(clock.instant());
  }

  @Transactional
  public void bookmark(Long userId, Long postingId, boolean on) {
    owned(userId, postingId);
    PostingUserState state = state(userId, postingId);
    if (on) {
      state.bookmark(clock.instant());
    } else {
      state.unbookmark();
    }
  }

  private PostingUserState state(Long userId, Long postingId) {
    return stateRepository
        .findByUserIdAndPostingId(userId, postingId)
        .orElseGet(() -> stateRepository.save(new PostingUserState(userId, postingId)));
  }

  private Posting owned(Long userId, Long postingId) {
    Posting posting =
        postingRepository
            .findById(postingId)
            .orElseThrow(() -> new BusinessException(ErrorCode.POSTING_NOT_FOUND));
    boolean mine =
        boardRepository
            .findById(posting.boardId())
            .map(board -> board.userId().equals(userId))
            .orElse(false);
    if (!mine) {
      throw new BusinessException(ErrorCode.POSTING_NOT_FOUND);
    }
    return posting;
  }
}
