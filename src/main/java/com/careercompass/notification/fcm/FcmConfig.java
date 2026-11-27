package com.careercompass.notification.fcm;

import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * FCM 발송기를 하나만 고른다 (#44). {@code app.fcm.credentials-path}(환경변수 {@code FCM_CREDENTIALS_PATH})가 비어
 * 있으면 로그만 남기는 구현, 있으면 실제 발송.
 *
 * <p>예전에는 두 구현이 각자 {@code @ConditionalOnProperty} 로 켜졌는데, 빈 문자열 havingValue 는 「값이 false 가 아님」으로만
 * 판정돼 경로를 주면 두 빈이 함께 떠 기동이 실패했다(AI 게이트웨이 #64 와 같은 함정).
 */
@Configuration
public class FcmConfig {

  private static final Logger log = LoggerFactory.getLogger(FcmConfig.class);

  @Bean
  FcmSender fcmSender(
      @Value("${app.fcm.credentials-path:${FCM_CREDENTIALS_PATH:}}") String credentialsPath)
      throws IOException {
    if (credentialsPath == null || credentialsPath.isBlank()) {
      log.info("FcmSender = LoggingFcmSender — 자격증명 없음, 푸시는 로그로만 남긴다");
      return new LoggingFcmSender();
    }
    log.info("FcmSender = FirebaseFcmSender — {}", credentialsPath);
    return new FirebaseFcmSender(credentialsPath);
  }
}
