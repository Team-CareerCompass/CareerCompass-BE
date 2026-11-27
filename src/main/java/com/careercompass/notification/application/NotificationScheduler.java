package com.careercompass.notification.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 알림 배치 (#46 #47) — 테스트에서는 property 로 끄고 트리거 메서드를 직접 부른다. */
@Component
@ConditionalOnProperty(
    name = "app.notification.worker-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class NotificationScheduler {

  private final NotificationService notificationService;
  private final NotificationTriggers triggers;

  public NotificationScheduler(
      NotificationService notificationService, NotificationTriggers triggers) {
    this.notificationService = notificationService;
    this.triggers = triggers;
  }

  /** 방해 금지 보류분 발송 — 5분 주기. */
  @Scheduled(fixedDelayString = "${app.notification.flush-interval-ms:300000}")
  public void flushPending() {
    notificationService.flushPending();
  }

  /** 마감 임박 D-3·D-1 — 매일 09:00 KST. */
  @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Seoul")
  public void dueSoon() {
    triggers.dueSoonSweep();
  }

  /** 주간 리포트 — 매주 월 09:00 KST. */
  @Scheduled(cron = "0 0 9 * * MON", zone = "Asia/Seoul")
  public void weekly() {
    triggers.weeklySweep();
  }

  /** 보관 90일 초과 알림 정리 — 매일 04:00 KST. */
  @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
  public void cleanup() {
    notificationService.cleanupOld();
  }
}
