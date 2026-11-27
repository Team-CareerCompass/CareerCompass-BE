package com.careercompass.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 모든 엔드포인트의 응답 래퍼. 성공은 {@code {ok:true, data}}, 실패는 {@code {ok:false, error{code,message,field}}} —
 * API_SPEC_v0.1.md §공통 규약.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(boolean ok, T data, ErrorBody error) {

  public static <T> ApiResponse<T> success(T data) {
    return new ApiResponse<>(true, data, null);
  }

  public static ApiResponse<Void> error(ErrorCode code, String message, String field) {
    return new ApiResponse<>(false, null, new ErrorBody(code.name(), message, field));
  }

  public static ApiResponse<Void> error(ErrorCode code) {
    return error(code, code.defaultMessage(), null);
  }

  /** 실패 본문. field 는 검증 실패 시 문제가 된 필드명, 그 외엔 null 로 직렬화된다. */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record ErrorBody(String code, String message, String field) {}
}
