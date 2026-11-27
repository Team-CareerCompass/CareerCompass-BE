package com.careercompass.application.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/** 재생성·되돌리기 시 보관되는 이전 답변 (F4-3, 최대 3개 — 초과분은 오래된 것부터 삭제). */
@Entity
@Table(name = "application_item_version")
public class ApplicationItemVersion {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "application_item_id", nullable = false, updatable = false)
  private Long applicationItemId;

  private String answer;

  private String tone;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected ApplicationItemVersion() {}

  public ApplicationItemVersion(Long applicationItemId, String answer, String tone) {
    this.applicationItemId = applicationItemId;
    this.answer = answer;
    this.tone = tone;
  }

  @PrePersist
  void prePersist() {
    this.createdAt = Instant.now();
  }

  public Long id() {
    return id;
  }

  public Long applicationItemId() {
    return applicationItemId;
  }

  public String answer() {
    return answer;
  }

  public String tone() {
    return tone;
  }

  public Instant createdAt() {
    return createdAt;
  }
}
