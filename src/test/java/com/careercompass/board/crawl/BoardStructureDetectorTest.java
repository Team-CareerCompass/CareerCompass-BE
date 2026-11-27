package com.careercompass.board.crawl;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BoardStructureDetectorTest {

  private final BoardStructureDetector detector = new BoardStructureDetector();
  private final URI base = URI.create("https://board.example.com/notice");

  private Document doc(String html) {
    return Jsoup.parse(html, base.toString());
  }

  @Test
  @DisplayName("테이블형 게시판 — 행·제목·날짜 셀렉터가 잡히고 미리보기가 절대 URL 로 나온다")
  void detectsTableBoard() {
    StringBuilder rows = new StringBuilder();
    for (int i = 8; i >= 1; i--) {
      rows.append("<tr><td class=\"num\">")
          .append(i)
          .append("</td>")
          .append("<td class=\"subject\"><a href=\"/notice/")
          .append(i)
          .append("\">채용 공고 안내 제")
          .append(i)
          .append("호</a></td>")
          .append("<td class=\"date\">2026-08-1")
          .append(i % 10)
          .append("</td></tr>");
    }
    BoardStructureDetector.Detection detection =
        detector.detect(doc("<table class=\"list\"><tbody>" + rows + "</tbody></table>"), base);

    assertThat(detection.status()).isEqualTo("success");
    assertThat(detection.selectors().row()).contains("tr");
    assertThat(detection.selectors().title()).contains("a");
    assertThat(detection.selectors().date()).contains("date");
    assertThat(detection.preview()).hasSize(5);
    assertThat(detection.preview().get(0).url()).startsWith("https://board.example.com/notice/");
  }

  @Test
  @DisplayName("ul/li 형 게시판 — 날짜 없는 구조도 제목·링크만으로 성공한다 (날짜는 선택)")
  void detectsListBoardWithoutDates() {
    StringBuilder items = new StringBuilder();
    for (int i = 1; i <= 6; i++) {
      items
          .append("<li class=\"item\"><a href=\"/p/")
          .append(i)
          .append("\">공모전 접수 안내 ")
          .append(i)
          .append("차</a></li>");
    }
    BoardStructureDetector.Detection detection =
        detector.detect(doc("<ul class=\"posts\">" + items + "</ul>"), base);

    assertThat(detection.status()).isEqualTo("success");
    assertThat(detection.selectors().date()).isNull();
  }

  @Test
  @DisplayName("비밀번호 입력이 있는 페이지는 login_required")
  void detectsLoginPage() {
    BoardStructureDetector.Detection detection =
        detector.detect(doc("<form><input type=\"password\"></form>"), base);

    assertThat(detection.status()).isEqualTo("login_required");
  }

  @Test
  @DisplayName("빈 본문 + 프레임워크 루트는 spa, 그 외 인식 불가는 failed")
  void classifiesSpaAndFailed() {
    assertThat(
            detector
                .detect(
                    doc(
                        "<head><script src=\"a.js\"></script><script src=\"b.js\"></script>"
                            + "<script src=\"c.js\"></script></head><body><div id=\"root\"></div></body>"),
                    base)
                .status())
        .isEqualTo("spa");

    assertThat(detector.detect(doc("<body><p>게시글이 없는 일반 페이지입니다.</p></body>"), base).status())
        .isEqualTo("failed");
  }

  @Test
  @DisplayName("저장된 셀렉터로 목록을 다시 파싱할 수 있다 — 수집기(#24)가 쓰는 경로")
  void parsesListWithStoredSelectors() {
    StringBuilder rows = new StringBuilder();
    for (int i = 1; i <= 6; i++) {
      rows.append("<tr><td class=\"subject\"><a href=\"/notice/")
          .append(i)
          .append("\">장학 공지 ")
          .append(i)
          .append("호</a></td>")
          .append("<td class=\"date\">2026.09.0")
          .append(i)
          .append("</td></tr>");
    }
    Document document = doc("<table class=\"list\"><tbody>" + rows + "</tbody></table>");
    BoardStructureDetector.Detection detection = detector.detect(document, base);

    var items = detector.parseList(document, detection.selectors());

    assertThat(items).hasSize(6);
    assertThat(items.get(0).title()).contains("장학 공지");
    assertThat(items.get(0).date()).matches("2026-09-0\\d");
  }
}
