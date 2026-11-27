package com.careercompass.common.api;

import org.springframework.http.HttpStatus;

/** API_SPEC_v0.1.md §9 에러 코드 14종과 1:1. 코드·HTTP 상태를 여기서만 정의한다. */
public enum ErrorCode {
  INVALID_INPUT(HttpStatus.BAD_REQUEST, "요청 검증에 실패했습니다"),
  AUTH_REQUIRED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다"),
  AUTH_INVALID(HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰입니다"),
  PERMISSION_DENIED(HttpStatus.FORBIDDEN, "권한이 없습니다"),
  RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "리소스를 찾을 수 없습니다"),
  POSTING_NOT_FOUND(HttpStatus.NOT_FOUND, "공고를 찾을 수 없습니다"),
  DUPLICATE_BOARD(HttpStatus.CONFLICT, "이미 등록된 게시판입니다"),
  LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_ENTITY, "허용 개수를 초과했습니다"),
  PROFILE_INCOMPLETE(HttpStatus.UNPROCESSABLE_ENTITY, "프로필 정보가 부족해 처리할 수 없습니다"),
  PARSING_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, "공고 파싱에 실패했습니다"),
  BOARD_BLOCKED(HttpStatus.UNPROCESSABLE_ENTITY, "접근이 차단된 게시판입니다"),
  RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "호출 횟수를 초과했습니다"),
  LLM_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AI 처리에 일시적으로 실패했습니다"),
  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다");

  private final HttpStatus status;
  private final String defaultMessage;

  ErrorCode(HttpStatus status, String defaultMessage) {
    this.status = status;
    this.defaultMessage = defaultMessage;
  }

  public HttpStatus status() {
    return status;
  }

  public String defaultMessage() {
    return defaultMessage;
  }
}
