package com.careercompass.common.exception;

import com.careercompass.common.api.ErrorCode;

/** 비즈니스 룰 위반. 던지면 전역 핸들러가 ErrorCode 의 HTTP 상태·계약 본문으로 변환한다. */
public class BusinessException extends RuntimeException {

  private final ErrorCode code;
  private final String field;

  public BusinessException(ErrorCode code) {
    this(code, code.defaultMessage(), null);
  }

  public BusinessException(ErrorCode code, String message) {
    this(code, message, null);
  }

  public BusinessException(ErrorCode code, String message, String field) {
    super(message);
    this.code = code;
    this.field = field;
  }

  public ErrorCode code() {
    return code;
  }

  public String field() {
    return field;
  }
}
