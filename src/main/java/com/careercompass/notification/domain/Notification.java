package com.careercompass.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 알림함 기록 (§8). dedupKey 유니크로 같은 사건의 재발송을 막는다(#44 #47). pushStatus: pending(방해 금지 보류 포함) → sent |
 * skipped.
 */
@Entity
@Table(name = "notification")
public class Notification {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false, updatable = false)
  private Long userId;

  @Column(nullable = false, updatable = false)
  private String type;

  @Column(nullable = false)
  private String title;

  private String body;

  private String deeplink;

  @Column(name = "dedup_key", nullable = false, updatable = false)
  private String dedupKey;

  @Column(name = "is_read", nullable = false)
  private boolean isRead;

  @Column(name = "push_status", nullable = false)
  private String pushStatus = "pending";

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected Notification() {}

  public Notification(
      Long userId, String type, String title, String body, String deeplink, String dedupKey) {
    this.userId = userId;
    this.type = type;
    this.title = title;
    this.body = body;
    this.deeplink = deeplink;
    this.dedupKey = dedupKey;
  }

  @PrePersist
  void prePersist() {
    this.createdAt = Instant.now();
  }

  public Long id() {
    return id;
  }

  public Long userId() {
    return userId;
  }

  public String type() {
    return type;
  }

  public String title() {
    return title;
  }

  public String body() {
    return body;
  }

  public String deeplink() {
    return deeplink;
  }

  public boolean isRead() {
    return isRead;
  }

  public String pushStatus() {
    return pushStatus;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public void pushSent() {
    this.pushStatus = "sent";
  }

  public void pushSkipped() {
    this.pushStatus = "skipped";
  }
}
