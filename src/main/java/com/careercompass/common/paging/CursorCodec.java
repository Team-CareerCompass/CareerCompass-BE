package com.careercompass.common.paging;

import com.careercompass.common.api.ErrorCode;
import com.careercompass.common.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** 커서 ↔ 불투명 문자열(Base64URL(JSON)) 변환. 클라이언트는 내용을 해석하지 않는다. */
public final class CursorCodec {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private CursorCodec() {}

  public static String encode(Cursor cursor) {
    try {
      byte[] json = MAPPER.writeValueAsBytes(cursor);
      return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
    } catch (Exception e) {
      throw new IllegalStateException("cursor 인코딩 실패", e);
    }
  }

  /** 형식이 깨졌거나 정렬 축이 바뀐 커서는 INVALID_INPUT 으로 거부한다. */
  public static Cursor decode(String raw, String expectedSort) {
    Cursor cursor;
    try {
      byte[] json = Base64.getUrlDecoder().decode(raw.getBytes(StandardCharsets.UTF_8));
      cursor = MAPPER.readValue(json, Cursor.class);
    } catch (Exception e) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "cursor 형식이 올바르지 않습니다", "cursor");
    }
    if (cursor.sort() == null || !cursor.sort().equals(expectedSort)) {
      throw new BusinessException(
          ErrorCode.INVALID_INPUT, "정렬 기준이 바뀌어 cursor 를 사용할 수 없습니다", "cursor");
    }
    return cursor;
  }
}
