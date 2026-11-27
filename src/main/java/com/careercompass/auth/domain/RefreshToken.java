package com.careercompass.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 리프레시 토큰 레코드. 원문은 저장하지 않고 SHA-256 해시만 둔다. {@code familyId} 는 로그인 1회가 만드는 회전 계보 — 소비된 토큰이 유예 시간 밖에서
 * 재사용되면 계보 전체를 폐기한다 (#11).
 */
@Entity
@Table(name = "refresh_token")
public class RefreshToken {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false, updatable = false)
  private Long userId;

  @Column(name = "device_id", nullable = false, updatable = false)
  private String deviceId;

  @Column(name = "token_hash", nullable = false, updatable = false)
  private String tokenHash;

  @Column(name = "family_id", nullable = false, updatable = false)
  private UUID familyId;

  @Column(name = "issued_at", nullable = false, updatable = false)
  private Instant issuedAt;

  @Column(name = "expires_at", nullable = false, updatable = false)
  private Instant expiresAt;

  @Column(name = "rotated_at")
  private Instant rotatedAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  protected RefreshToken() {}

  public RefreshToken(
      Long userId,
      String deviceId,
      String tokenHash,
      UUID familyId,
      Instant issuedAt,
      Instant expiresAt) {
    this.userId = userId;
    this.deviceId = deviceId;
    this.tokenHash = tokenHash;
    this.familyId = familyId;
    this.issuedAt = issuedAt;
    this.expiresAt = expiresAt;
  }

  public Long userId() {
    return userId;
  }

  public String deviceId() {
    return deviceId;
  }

  public UUID familyId() {
    return familyId;
  }

  public Instant expiresAt() {
    return expiresAt;
  }

  public Instant rotatedAt() {
    return rotatedAt;
  }

  public boolean revoked() {
    return revokedAt != null;
  }

  public boolean expired(Instant now) {
    return !now.isBefore(expiresAt);
  }

  public boolean rotated() {
    return rotatedAt != null;
  }

  public void markRotated(Instant at) {
    this.rotatedAt = at;
  }

  public void revoke(Instant at) {
    if (this.revokedAt == null) {
      this.revokedAt = at;
    }
  }
}
