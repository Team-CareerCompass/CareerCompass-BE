package com.careercompass.common.exception;

import com.careercompass.common.api.ApiResponse;
import com.careercompass.common.api.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
    return ResponseEntity.status(e.code().status())
        .body(ApiResponse.error(e.code(), e.getMessage(), e.field()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
    FieldError fieldError = e.getBindingResult().getFieldError();
    String field = fieldError != null ? fieldError.getField() : null;
    String message =
        fieldError != null && fieldError.getDefaultMessage() != null
            ? fieldError.getDefaultMessage()
            : ErrorCode.INVALID_INPUT.defaultMessage();
    return invalidInput(message, field);
  }

  @ExceptionHandler(HandlerMethodValidationException.class)
  public ResponseEntity<ApiResponse<Void>> handleMethodValidation(
      HandlerMethodValidationException e) {
    return invalidInput(ErrorCode.INVALID_INPUT.defaultMessage(), null);
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(
      MethodArgumentTypeMismatchException e) {
    return invalidInput("파라미터 형식이 올바르지 않습니다", e.getName());
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<ApiResponse<Void>> handleMissingParam(
      MissingServletRequestParameterException e) {
    return invalidInput("필수 파라미터가 없습니다", e.getParameterName());
  }

  @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
  public ResponseEntity<ApiResponse<Void>> handleUnreadable() {
    return invalidInput("요청 본문을 읽을 수 없습니다", null);
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<ApiResponse<Void>> handleUploadSize() {
    return invalidInput("파일은 10MB 이하여야 합니다", "file");
  }

  @ExceptionHandler({NoResourceFoundException.class, HttpRequestMethodNotSupportedException.class})
  public ResponseEntity<ApiResponse<Void>> handleNotFound() {
    return ResponseEntity.status(ErrorCode.RESOURCE_NOT_FOUND.status())
        .body(ApiResponse.error(ErrorCode.RESOURCE_NOT_FOUND));
  }

  /** 예상 못 한 예외 — 내부 정보는 로그에만 남기고 본문은 일반 메시지로 접는다 (issue #3). */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
    log.error("unhandled exception", e);
    return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.status())
        .body(ApiResponse.error(ErrorCode.INTERNAL_ERROR));
  }

  private ResponseEntity<ApiResponse<Void>> invalidInput(String message, String field) {
    return ResponseEntity.status(ErrorCode.INVALID_INPUT.status())
        .body(ApiResponse.error(ErrorCode.INVALID_INPUT, message, field));
  }
}
