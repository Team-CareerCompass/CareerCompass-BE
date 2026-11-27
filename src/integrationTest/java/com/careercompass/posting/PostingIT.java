package com.careercompass.posting;

import static org.assertj.core.api.Assertions.assertThat;

import com.careercompass.support.ApiITBase;
import com.careercompass.support.TestFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** M4 — 공고 목록 필터/정렬/커서(#28), 상세+유사 공고(#29), 읽음/북마크(#30), parseStatus 계약(#27). */
class PostingIT extends ApiITBase {

  @Autowired private JdbcTemplate jdbc;

  private long posting(
      long boardId, String title, String type, LocalDate due, int collectedSecondsAgo) {
    return jdbc.queryForObject(
        "insert into posting (board_id, title, url, url_hash, raw_content, type, due_date,"
            + " collected_at) values (?, ?, ?, ?, '본문', ?, ?, now() - make_interval(secs => ?))"
            + " returning id",
        Long.class,
        boardId,
        title,
        "https://t.local/" + UUID.randomUUID(),
        UUID.randomUUID().toString(),
        type,
        due,
        collectedSecondsAgo);
  }

  private void parsed(long postingId, String status, String keywordsJson) {
    jdbc.update(
        "insert into posting_parsed (posting_id, parse_status, keywords) values (?, ?, ?::jsonb)",
        postingId,
        status,
        keywordsJson);
  }

  private void score(long userId, long postingId, int score, String label) {
    jdbc.update(
        "insert into suitability_score (user_id, posting_id, score, label, breakdown)"
            + " values (?, ?, ?, ?, '[]'::jsonb)",
        userId,
        postingId,
        score,
        label);
  }

  private JsonNode list(Session session, String query) {
    return json(call(HttpMethod.GET, "/postings" + query, null, session.accessToken()))
        .path("data");
  }

  @Test
  @DisplayName("기본 목록 — 마감 지난 공고는 숨고, 수집 최신순 + parseStatus 가 실린다 (#27 #28)")
  void defaultListHidesExpired() {
    Session session = login();
    long boardId =
        TestFixtures.board(jdbc, session.userId(), "https://b.local/" + UUID.randomUUID());
    long fresh = posting(boardId, "신규 채용 공고", "recruit", LocalDate.now().plusDays(7), 10);
    long expired = posting(boardId, "지난 공고", "recruit", LocalDate.now().minusDays(1), 5);
    long noDue = posting(boardId, "마감일 없는 공고", "other", null, 20);
    parsed(fresh, "pending", null);

    JsonNode data = list(session, "");

    List<Long> ids = new ArrayList<>();
    data.path("postings").forEach(p -> ids.add(p.path("id").asLong()));
    assertThat(ids).containsExactly(expiredHidden(fresh, noDue, expired));
    assertThat(data.path("postings").get(0).path("parseStatus").asText()).isEqualTo("pending");
    assertThat(data.path("postings").get(0).path("board").path("name").asText()).isNotBlank();

    JsonNode withExpired = list(session, "?includeExpired=true");
    assertThat(withExpired.path("postings")).hasSize(3);
  }

  private List<Long> ids(Session session, String query) {
    List<Long> ids = new ArrayList<>();
    list(session, query).path("postings").forEach(p -> ids.add(p.path("id").asLong()));
    return ids;
  }

  @Test
  @DisplayName("마감일 프리셋 다섯 — 범위가 오면 숨김을 얹지 않고, 마감일 없는 공고는 includeNoDueDate 로 가른다 (#56)")
  void duePresets() {
    Session session = login();
    long boardId =
        TestFixtures.board(jdbc, session.userId(), "https://b.local/" + UUID.randomUUID());
    LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
    long in3 = posting(boardId, "3일 뒤 마감", "recruit", today.plusDays(3), 10);
    long in20 = posting(boardId, "20일 뒤 마감", "recruit", today.plusDays(20), 20);
    long past = posting(boardId, "지난달 마감", "recruit", today.minusDays(30), 30);
    long noDue = posting(boardId, "상시 모집", "recruit", null, 40);

    // 전체: dueFrom=오늘 + includeNoDueDate=true
    assertThat(ids(session, "?dueFrom=" + today + "&includeNoDueDate=true"))
        .containsExactlyInAnyOrder(in3, in20, noDue);
    // 7일 이내
    assertThat(
            ids(
                session,
                "?dueFrom=" + today + "&dueTo=" + today.plusDays(7) + "&includeNoDueDate=false"))
        .containsExactly(in3);
    // 30일 이내
    assertThat(
            ids(
                session,
                "?dueFrom=" + today + "&dueTo=" + today.plusDays(30) + "&includeNoDueDate=false"))
        .containsExactlyInAnyOrder(in3, in20);
    // 마감 지난 공고 포함
    assertThat(ids(session, "?includeExpired=true&includeNoDueDate=true"))
        .containsExactlyInAnyOrder(in3, in20, past, noDue);
    // 직접 지정 — 한쪽 끝만(상한만): 지난 범위도 숨김 없이 나온다
    assertThat(ids(session, "?dueTo=" + today.minusDays(1) + "&includeNoDueDate=false"))
        .containsExactly(past);
    // 기본(파라미터 없음) — 지난 마감은 숨고 마감일 없는 공고는 나온다
    assertThat(ids(session, "")).containsExactlyInAnyOrder(in3, in20, noDue);
  }

  @Test
  @DisplayName("검색어 q — 제목과 parsed.keywords 부분 일치, 원문은 뒤지지 않는다 (#56)")
  void searchTitleAndKeywords() {
    Session session = login();
    long boardId =
        TestFixtures.board(jdbc, session.userId(), "https://b.local/" + UUID.randomUUID());
    long byTitle = posting(boardId, "빅데이터 분석가 채용", "recruit", null, 10);
    long byKeyword = posting(boardId, "2026 신입 공채", "recruit", null, 20);
    long rawOnly = posting(boardId, "장학생 선발", "scholarship", null, 30);
    parsed(byKeyword, "done", "[\"데이터 엔지니어\",\"Spark\",\"SQL\"]");
    jdbc.update("update posting set raw_content = '데이터 관련 전공 우대' where id = ?", rawOnly);

    assertThat(ids(session, "?q=데이터")).containsExactlyInAnyOrder(byTitle, byKeyword);
    assertThat(ids(session, "?q=spark")).containsExactly(byKeyword); // 대소문자 무시
    assertThat(ids(session, "?q=100%25")).isEmpty(); // LIKE 와일드카드는 글자 그대로
  }

  @Test
  @DisplayName("공고가 아닌 글(parseStatus=excluded)은 목록에 뜨지 않는다 (#65)")
  void excludedPostingsHidden() {
    Session session = login();
    long boardId =
        TestFixtures.board(jdbc, session.userId(), "https://b.local/" + UUID.randomUUID());
    long real = posting(boardId, "2026 백엔드 인턴 채용", "recruit", null, 10);
    long notice = posting(boardId, "2026-2 수강신청 안내", "other", null, 5);
    parsed(real, "done", "[\"백엔드\",\"Spring\",\"AWS\"]");
    parsed(notice, "excluded", null);

    List<Long> ids = new ArrayList<>();
    list(session, "?includeExpired=true")
        .path("postings")
        .forEach(p -> ids.add(p.path("id").asLong()));
    assertThat(ids).containsExactly(real);
  }

  private Long[] expiredHidden(long fresh, long noDue, long expired) {
    // collected_at: expired(5초 전) < fresh(10초 전) < noDue(20초 전) — expired 는 숨겨진다
    return new Long[] {fresh, noDue};
  }

  @Test
  @DisplayName("필터 조합 — types·minScore·unreadOnly·bookmarkedOnly·검색어 (#28 #30)")
  void filters() {
    Session session = login();
    long boardId =
        TestFixtures.board(jdbc, session.userId(), "https://b.local/" + UUID.randomUUID());
    long scholarship = posting(boardId, "장학금 신청 안내", "scholarship", null, 30);
    long recruit = posting(boardId, "카카오 백엔드 채용", "recruit", null, 20);
    long contest = posting(boardId, "AI 공모전", "contest", null, 10);
    score(session.userId(), scholarship, 85, "very_suitable");
    score(session.userId(), recruit, 55, "normal");

    assertThat(list(session, "?types=scholarship").path("postings")).hasSize(1);
    assertThat(list(session, "?minScore=80").path("postings")).hasSize(1);
    assertThat(list(session, "?q=백엔드").path("postings")).hasSize(1);

    call(HttpMethod.POST, "/postings/" + recruit + "/read", null, session.accessToken());
    JsonNode unread = list(session, "?unreadOnly=true");
    assertThat(unread.path("postings")).hasSize(2);

    call(HttpMethod.POST, "/postings/" + contest + "/bookmark", null, session.accessToken());
    JsonNode bookmarked = list(session, "?bookmarkedOnly=true");
    assertThat(bookmarked.path("postings")).hasSize(1);
    assertThat(bookmarked.path("postings").get(0).path("id").asLong()).isEqualTo(contest);
  }

  @Test
  @DisplayName("커서 페이징 — limit 2 로 전량 순회 시 중복·누락 없음 (#28)")
  void cursorPagingNoDupNoLoss() {
    Session session = login();
    long boardId =
        TestFixtures.board(jdbc, session.userId(), "https://b.local/" + UUID.randomUUID());
    Set<Long> all = new LinkedHashSet<>();
    for (int i = 0; i < 5; i++) {
      all.add(posting(boardId, "공고 " + i, "other", null, 100 - i));
    }

    Set<Long> seen = new LinkedHashSet<>();
    String cursor = null;
    int pages = 0;
    do {
      JsonNode data = list(session, "?limit=2" + (cursor == null ? "" : "&cursor=" + cursor));
      data.path("postings").forEach(p -> seen.add(p.path("id").asLong()));
      cursor = data.path("nextCursor").isNull() ? null : data.path("nextCursor").asText();
      pages++;
    } while (cursor != null && pages < 10);

    assertThat(pages).isEqualTo(3);
    assertThat(seen).containsExactlyInAnyOrderElementsOf(all);
  }

  @Test
  @DisplayName("정렬 — due_asc 는 마감일 있는 공고만 오름차순, score_desc 는 점수 있는 공고만 내림차순 (#28)")
  void sortAxes() {
    Session session = login();
    long boardId =
        TestFixtures.board(jdbc, session.userId(), "https://b.local/" + UUID.randomUUID());
    long dueSoon = posting(boardId, "마감 임박", "recruit", LocalDate.now().plusDays(1), 10);
    long dueLater = posting(boardId, "마감 여유", "recruit", LocalDate.now().plusDays(10), 20);
    long noDue = posting(boardId, "마감 없음", "recruit", null, 30);
    score(session.userId(), dueLater, 90, "very_suitable");
    score(session.userId(), dueSoon, 70, "suitable");

    JsonNode byDue = list(session, "?sort=due_asc");
    assertThat(byDue.path("postings")).hasSize(2);
    assertThat(byDue.path("postings").get(0).path("id").asLong()).isEqualTo(dueSoon);

    JsonNode byScore = list(session, "?sort=score_desc");
    assertThat(byScore.path("postings")).hasSize(2);
    assertThat(byScore.path("postings").get(0).path("score").asInt()).isEqualTo(90);
  }

  @Test
  @DisplayName("상세 — 파싱 완료면 parsed·suitability·유사 공고(키워드 겹침)가 내려온다 (#29)")
  void detailWithParsedAndSimilar() {
    Session session = login();
    long boardId =
        TestFixtures.board(jdbc, session.userId(), "https://b.local/" + UUID.randomUUID());
    long target = posting(boardId, "백엔드 채용 A", "recruit", LocalDate.now().plusDays(5), 10);
    long similarByKeyword =
        posting(boardId, "서버 개발 채용 B", "recruit", LocalDate.now().plusDays(6), 20);
    long otherType = posting(boardId, "디자인 공모전", "contest", LocalDate.now().plusDays(6), 30);
    parsed(target, "done", "[\"Spring\",\"Java\"]");
    parsed(similarByKeyword, "done", "[\"Spring\",\"Kotlin\"]");
    score(session.userId(), target, 88, "very_suitable");

    JsonNode data =
        json(call(HttpMethod.GET, "/postings/" + target, null, session.accessToken())).path("data");

    assertThat(data.path("parseStatus").asText()).isEqualTo("done");
    assertThat(data.path("parsed").path("keywords")).hasSize(2);
    assertThat(data.path("suitability").path("score").asInt()).isEqualTo(88);
    assertThat(data.path("canGenerateApplication").asBoolean()).isTrue();
    assertThat(data.path("similar").get(0).path("id").asLong()).isEqualTo(similarByKeyword);
    for (JsonNode s : data.path("similar")) {
      assertThat(s.path("id").asLong()).isNotEqualTo(otherType);
    }
  }

  @Test
  @DisplayName("상세 — 파싱 전/실패 상태에서는 parsed=null·suitability=null, 공모전은 지원서 버튼 비노출 (#27 #29)")
  void detailPendingAndFailed() {
    Session session = login();
    long boardId =
        TestFixtures.board(jdbc, session.userId(), "https://b.local/" + UUID.randomUUID());
    long pending = posting(boardId, "파싱 전 공모전", "contest", null, 10);
    long failed = posting(boardId, "파싱 실패 공고", "recruit", null, 20);
    parsed(pending, "pending", null);
    parsed(failed, "failed", null);

    JsonNode pendingData =
        json(call(HttpMethod.GET, "/postings/" + pending, null, session.accessToken()))
            .path("data");
    assertThat(pendingData.path("parseStatus").asText()).isEqualTo("pending");
    assertThat(pendingData.path("parsed").isNull()).isTrue();
    assertThat(pendingData.path("suitability").isNull()).isTrue();
    assertThat(pendingData.path("canGenerateApplication").asBoolean()).isFalse();

    JsonNode failedData =
        json(call(HttpMethod.GET, "/postings/" + failed, null, session.accessToken())).path("data");
    assertThat(failedData.path("parseStatus").asText()).isEqualTo("failed");
  }

  @Test
  @DisplayName("읽음·북마크 멱등 — 중복 호출해도 상태가 망가지지 않는다 (#30)")
  void idempotentStateOps() {
    Session session = login();
    long boardId =
        TestFixtures.board(jdbc, session.userId(), "https://b.local/" + UUID.randomUUID());
    long postingId = posting(boardId, "상태 테스트", "recruit", null, 10);

    for (int i = 0; i < 2; i++) {
      assertThat(
              call(HttpMethod.POST, "/postings/" + postingId + "/read", null, session.accessToken())
                  .getStatusCode()
                  .value())
          .isEqualTo(200);
      assertThat(
              call(
                      HttpMethod.POST,
                      "/postings/" + postingId + "/bookmark",
                      null,
                      session.accessToken())
                  .getStatusCode()
                  .value())
          .isEqualTo(200);
    }
    Integer stateRows =
        jdbc.queryForObject(
            "select count(*) from posting_user_state where user_id = ? and posting_id = ?",
            Integer.class,
            session.userId(),
            postingId);
    assertThat(stateRows).isEqualTo(1);

    call(HttpMethod.DELETE, "/postings/" + postingId + "/bookmark", null, session.accessToken());
    JsonNode detail =
        json(call(HttpMethod.GET, "/postings/" + postingId, null, session.accessToken()))
            .path("data");
    assertThat(detail.path("isRead").asBoolean()).isTrue();
    assertThat(detail.path("isBookmarked").asBoolean()).isFalse();
  }

  @Test
  @DisplayName("남의 공고는 POSTING_NOT_FOUND(404)")
  void othersPostingHidden() {
    Session owner = login();
    long boardId = TestFixtures.board(jdbc, owner.userId(), "https://b.local/" + UUID.randomUUID());
    long postingId = posting(boardId, "남의 공고", "recruit", null, 10);

    Session stranger = login();
    ResponseEntity<String> res =
        call(HttpMethod.GET, "/postings/" + postingId, null, stranger.accessToken());

    assertThat(res.getStatusCode().value()).isEqualTo(404);
    assertThat(json(res).path("error").path("code").asText()).isEqualTo("POSTING_NOT_FOUND");
  }
}
