package com.careercompass.common.paging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.careercompass.common.exception.BusinessException;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CursorPageTest {

  private record Row(long id) {}

  private static Cursor cursorOf(Row row) {
    return new Cursor("collected_desc", List.of(row.id()), row.id());
  }

  @Test
  @DisplayName("빈 결과 — items 비고 nextCursor 는 null")
  void emptyResult() {
    CursorPage<Row> page = CursorPage.of(List.of(), 20, CursorPageTest::cursorOf);

    assertThat(page.items()).isEmpty();
    assertThat(page.nextCursor()).isNull();
  }

  @Test
  @DisplayName("정확히 limit 개 — 전부 내리고 nextCursor 는 null (마지막 페이지)")
  void exactlyLimit() {
    List<Row> rows = IntStream.rangeClosed(1, 20).mapToObj(Row::new).map(r -> (Row) r).toList();

    CursorPage<Row> page = CursorPage.of(rows, 20, CursorPageTest::cursorOf);

    assertThat(page.items()).hasSize(20);
    assertThat(page.nextCursor()).isNull();
  }

  @Test
  @DisplayName("limit+1 개 조회 — limit 개로 자르고 마지막 항목 기준 nextCursor 를 준다")
  void hasNextPage() {
    List<Row> rows = IntStream.rangeClosed(1, 21).mapToObj(Row::new).map(r -> (Row) r).toList();

    CursorPage<Row> page = CursorPage.of(rows, 20, CursorPageTest::cursorOf);

    assertThat(page.items()).hasSize(20);
    assertThat(page.nextCursor()).isNotBlank();
    Cursor next = CursorCodec.decode(page.nextCursor(), "collected_desc");
    assertThat(next.id()).isEqualTo(20L); // tie-breaker 로 마지막 항목의 id 가 실린다
  }

  @Test
  @DisplayName("limit 은 기본 20, 상한 50 으로 자르고, 1 미만은 거부한다")
  void limitResolution() {
    assertThat(PageLimit.resolve(null)).isEqualTo(20);
    assertThat(PageLimit.resolve(35)).isEqualTo(35);
    assertThat(PageLimit.resolve(999)).isEqualTo(50);
    assertThatThrownBy(() -> PageLimit.resolve(0)).isInstanceOf(BusinessException.class);
  }
}
