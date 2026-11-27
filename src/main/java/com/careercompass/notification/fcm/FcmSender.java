package com.careercompass.notification.fcm;

/** FCM 발송 경계 (#44). 자격증명 미설정 환경(로컬·테스트)은 {@link LoggingFcmSender} 가 대신한다. */
public interface FcmSender {

  enum Result {
    SENT,
    UNREGISTERED, // 죽은 토큰 — 호출측이 정리한다
    FAILED
  }

  Result send(String fcmToken, String title, String body, String deeplink);
}
