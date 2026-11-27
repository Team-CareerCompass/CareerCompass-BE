package com.careercompass.application.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

/** 지원서 질문 항목. status: loading(생성 중) | done | failed. 글자 수 초과 저장은 서버가 막지 않는다 (F4-3). */
@Entity
@Table(name = "application_item")
public class ApplicationItem {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "application_id", nullable = false, updatable = false)
  private Long applicationId;

  @Column(name = "item_order", nullable = false)
  private int itemOrder;

  @Column(nullable = false)
  private String question;

  @Column(name = "max_chars")
  private Integer maxChars;

  private String answer;

  @Column(nullable = false)
  private String status = "loading";

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected ApplicationItem() {}

  public ApplicationItem(Long applicationId, int itemOrder, String question, Integer maxChars) {
    this.applicationId = applicationId;
    this.itemOrder = itemOrder;
    this.question = question;
    this.maxChars = maxChars;
  }

  @PrePersist
  @PreUpdate
  void touch() {
    this.updatedAt = Instant.now();
  }

  public Long id() {
    return id;
  }

  public Long applicationId() {
    return applicationId;
  }

  public int itemOrder() {
    return itemOrder;
  }

  public String question() {
    return question;
  }

  public Integer maxChars() {
    return maxChars;
  }

  public String answer() {
    return answer;
  }

  public String status() {
    return status;
  }

  public void draftReady(String answer) {
    this.answer = answer;
    this.status = "done";
  }

  public void draftFailed() {
    this.status = "failed"; // 이전 답(answer)은 지우지 않는다 — 재생성 실패에도 사용자의 본문이 남는다
  }

  /** 재생성 시작 (#38) — 생성 중 표시만 하고 지금 답은 둔다. 성공하면 버전으로 보관된 뒤 교체된다. */
  public void startRegenerate() {
    this.status = "loading";
  }

  public void edit(String answer) {
    this.answer = answer;
    this.status = "done";
  }
}
