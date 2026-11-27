package com.careercompass.common.paging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.careercompass.common.api.ErrorCode;
import com.careercompass.common.exception.BusinessException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CursorCodecTest {

  @Test
  @DisplayName("인코딩한 커서는 같은 정렬 축으로 디코딩하면 원본과 같다")
  void roundTrip() {
    Cursor original = new Cursor("collected_desc", List.of("2026-05-18T07:00:00Z", 33), 101L);
    String encoded = CursorCodec.encode(original);

    Cursor decoded = CursorCodec.decode(encoded, "collected_desc");

    assertThat(decoded.sort()).isEqualTo("collected_desc");
    assertThat(decoded.keys()).containsExactly("2026-05-18T07:00:00Z", 33);
    assertThat(decoded.id()).isEqualTo(101L);
  }

  @Test
  @DisplayName("형식이 깨진 커서는 INVALID_INPUT(field=cursor) 으로 거부한다")
  void garbageRejected() {
    assertThatThrownBy(() -> CursorCodec.decode("!!!not-base64!!!", "collected_desc"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> {
              assertThat(e.code()).isEqualTo(ErrorCode.INVALID_INPUT);
              assertThat(e.field()).isEqualTo("cursor");
            });
  }

  @Test
  @DisplayName("정렬 축이 바뀐 커서는 거부한다 — 축이 다르면 키 의미가 달라진다")
  void sortMismatchRejected() {
    String encoded = CursorCodec.encode(new Cursor("due_asc", List.of("2026-05-25"), 7L));

    assertThatThrownBy(() -> CursorCodec.decode(encoded, "score_desc"))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_INPUT));
  }
}
