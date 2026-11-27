package com.careercompass.common.paging;

import java.util.List;
import java.util.function.Function;

/**
 * 커서 페이징 결과. 마지막 페이지면 {@code nextCursor} 는 null 로 직렬화된다 — FE 는 이것으로 무한 스크롤을 멈춘다 (issue #4).
 *
 * <p>사용법: limit+1 개를 조회한 뒤 {@link #of} 에 넘긴다.
 */
public record CursorPage<T>(List<T> items, String nextCursor) {

  public static <T> CursorPage<T> of(List<T> fetched, int limit, Function<T, Cursor> cursorOf) {
    if (fetched.size() <= limit) {
      return new CursorPage<>(List.copyOf(fetched), null);
    }
    List<T> items = List.copyOf(fetched.subList(0, limit));
    Cursor last = cursorOf.apply(items.get(items.size() - 1));
    return new CursorPage<>(items, CursorCodec.encode(last));
  }
}
