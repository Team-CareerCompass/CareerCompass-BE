package com.careercompass.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

/** 모델 개선 활용 동의 (F4-4, #48) — pass 결과 데이터의 분석 활용·senior 코호트 포함 여부를 가른다. */
@Entity
@Table(name = "user_consent")
public class UserConsent {

  @Id
  @Column(name = "user_id")
  private Long userId;

  @Column(name = "model_improvement", nullable = false)
  private boolean modelImprovement;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected UserConsent() {}

  public UserConsent(Long userId) {
    this.userId = userId;
  }

  @PrePersist
  @PreUpdate
  void touch() {
    this.updatedAt = Instant.now();
  }

  public boolean modelImprovement() {
    return modelImprovement;
  }

  public void setModelImprovement(boolean value) {
    this.modelImprovement = value;
  }
}
