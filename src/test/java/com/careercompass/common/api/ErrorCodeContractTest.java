package com.careercompass.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ErrorCodeContractTest {

  @Test
  @DisplayName("에러 코드 14종의 HTTP 상태가 API_SPEC §9 표와 1:1 로 일치한다")
  void errorCodesMatchSpecTable() {
    Map<ErrorCode, Integer> spec =
        Map.ofEntries(
            Map.entry(ErrorCode.INVALID_INPUT, 400),
            Map.entry(ErrorCode.AUTH_REQUIRED, 401),
            Map.entry(ErrorCode.AUTH_INVALID, 401),
            Map.entry(ErrorCode.PERMISSION_DENIED, 403),
            Map.entry(ErrorCode.RESOURCE_NOT_FOUND, 404),
            Map.entry(ErrorCode.POSTING_NOT_FOUND, 404),
            Map.entry(ErrorCode.DUPLICATE_BOARD, 409),
            Map.entry(ErrorCode.LIMIT_EXCEEDED, 422),
            Map.entry(ErrorCode.PROFILE_INCOMPLETE, 422),
            Map.entry(ErrorCode.PARSING_FAILED, 422),
            Map.entry(ErrorCode.BOARD_BLOCKED, 422),
            Map.entry(ErrorCode.RATE_LIMITED, 429),
            Map.entry(ErrorCode.LLM_UNAVAILABLE, 503),
            Map.entry(ErrorCode.INTERNAL_ERROR, 500));

    assertThat(ErrorCode.values()).hasSize(spec.size());
    for (ErrorCode code : ErrorCode.values()) {
      assertThat(code.status().value()).as("%s 의 HTTP 상태", code).isEqualTo(spec.get(code));
      assertThat(code.defaultMessage()).as("%s 의 기본 메시지", code).isNotBlank();
    }
  }
}
