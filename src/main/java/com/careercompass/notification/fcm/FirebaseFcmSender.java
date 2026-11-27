package com.careercompass.notification.fcm;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import java.io.FileInputStream;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 실제 FCM 발송 (#44) — 서비스 계정 JSON 경로(app.fcm.credentials-path)가 설정된 환경에서만 활성화된다. */
public class FirebaseFcmSender implements FcmSender {

  private static final Logger log = LoggerFactory.getLogger(FirebaseFcmSender.class);

  public FirebaseFcmSender(String credentialsPath) throws IOException {
    if (FirebaseApp.getApps().isEmpty()) {
      try (FileInputStream credentials = new FileInputStream(credentialsPath)) {
        FirebaseApp.initializeApp(
            FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.fromStream(credentials))
                .build());
      }
    }
  }

  @Override
  public Result send(String fcmToken, String title, String body, String deeplink) {
    try {
      Message message =
          Message.builder()
              .setToken(fcmToken)
              .putData("title", title)
              .putData("body", body == null ? "" : body)
              .putData("deeplink", deeplink == null ? "" : deeplink)
              .build();
      FirebaseMessaging.getInstance().send(message);
      return Result.SENT;
    } catch (FirebaseMessagingException e) {
      if (e.getMessagingErrorCode() == MessagingErrorCode.UNREGISTERED) {
        return Result.UNREGISTERED;
      }
      log.warn("FCM 발송 실패: {}", e.getMessagingErrorCode(), e);
      return Result.FAILED;
    }
  }
}
