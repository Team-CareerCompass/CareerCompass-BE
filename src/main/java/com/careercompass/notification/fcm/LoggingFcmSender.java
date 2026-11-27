package com.careercompass.notification.fcm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 자격증명이 없는 환경의 기본 구현 — 발송 내용을 로그로만 남긴다. */
public class LoggingFcmSender implements FcmSender {

  private static final Logger log = LoggerFactory.getLogger(LoggingFcmSender.class);

  @Override
  public Result send(String fcmToken, String title, String body, String deeplink) {
    log.info("[FCM-STUB] token={}... title={} deeplink={}", head(fcmToken), title, deeplink);
    return Result.SENT;
  }

  private String head(String token) {
    return token == null ? "null" : token.substring(0, Math.min(8, token.length()));
  }
}
