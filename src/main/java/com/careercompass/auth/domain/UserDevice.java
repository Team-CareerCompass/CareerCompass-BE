package com.careercompass.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/** 사용자×기기 레코드 — FCM 토큰과 지문 로그인 등록이 계정별로 귀속된다 (#10, #13). */
@Entity
@Table(name = "user_device")
public class UserDevice {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false, updatable = false)
  private Long userId;

  @Column(name = "device_id", nullable = false, updatable = false)
  private String deviceId;

  @Column(name = "fcm_token")
  private String fcmToken;

  @Column(name = "biometric_enabled", nullable = false)
  private boolean biometricEnabled;

  @Column(name = "last_login_at")
  private Instant lastLoginAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected UserDevice() {}

  public UserDevice(Long userId, String deviceId) {
    this.userId = userId;
    this.deviceId = deviceId;
  }

  @PrePersist
  void prePersist() {
    this.createdAt = Instant.now();
  }

  public Long userId() {
    return userId;
  }

  public String deviceId() {
    return deviceId;
  }

  public String fcmToken() {
    return fcmToken;
  }

  public boolean biometricEnabled() {
    return biometricEnabled;
  }

  public void loggedIn(String fcmToken, Instant at) {
    this.fcmToken = fcmToken;
    this.lastLoginAt = at;
  }

  public void enableBiometric() {
    this.biometricEnabled = true;
  }

  /** 로그아웃 — 이 기기의 FCM 발송을 멈추고 지문 등록을 폐기한다 (#12, #13). */
  public void loggedOut() {
    this.fcmToken = null;
    this.biometricEnabled = false;
  }
}
