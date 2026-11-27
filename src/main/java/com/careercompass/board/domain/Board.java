package com.careercompass.board.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 등록 게시판. {@code isActive} 는 사용자의 켜고 끄기, {@code status}(active|error) 는 시스템 상태 — FE 는 이 둘로 「사용자가
 * 일시중지」와 「수집 오류로 꺼짐」을 구분한다 (#23, #25). 수집 대상 = isActive && status=active.
 */
@Entity
@Table(name = "board")
public class Board {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false, updatable = false)
  private Long userId;

  @Column(nullable = false)
  private String url;

  @Column(nullable = false)
  private String name;

  @Column(nullable = false)
  private String type;

  @JdbcTypeCode(SqlTypes.JSON)
  private Map<String, Object> selectors;

  @Column(name = "cycle_hours", nullable = false)
  private int cycleHours = 24;

  @Column(nullable = false)
  private String status = "active";

  @Column(name = "is_active", nullable = false)
  private boolean isActive = true;

  @Column(name = "fail_count", nullable = false)
  private int failCount;

  @Column(name = "last_collected_at")
  private Instant lastCollectedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected Board() {}

  public Board(
      Long userId,
      String url,
      String name,
      String type,
      int cycleHours,
      Map<String, Object> selectors) {
    this.userId = userId;
    this.url = url;
    this.name = name;
    this.type = type;
    this.cycleHours = cycleHours;
    this.selectors = selectors;
  }

  @PrePersist
  void prePersist() {
    Instant now = Instant.now();
    this.createdAt = now;
    this.updatedAt = now;
  }

  @PreUpdate
  void preUpdate() {
    this.updatedAt = Instant.now();
  }

  public Long id() {
    return id;
  }

  public Long userId() {
    return userId;
  }

  public String url() {
    return url;
  }

  public String name() {
    return name;
  }

  public String type() {
    return type;
  }

  public Map<String, Object> selectors() {
    return selectors;
  }

  public int cycleHours() {
    return cycleHours;
  }

  public String status() {
    return status;
  }

  public boolean isActive() {
    return isActive;
  }

  public int failCount() {
    return failCount;
  }

  public Instant lastCollectedAt() {
    return lastCollectedAt;
  }

  public boolean crawlable() {
    return isActive && "active".equals(status);
  }

  public void update(String name, String type, Integer cycleHours, Boolean isActive) {
    if (name != null) {
      this.name = name;
    }
    if (type != null) {
      this.type = type;
    }
    if (cycleHours != null) {
      this.cycleHours = cycleHours;
    }
    if (isActive != null) {
      this.isActive = isActive;
    }
  }

  public void collectSucceeded(Instant at) {
    this.failCount = 0;
    this.status = "active";
    this.lastCollectedAt = at;
  }

  /**
   * @return true 면 이번 실패로 3연속에 도달해 비활성화됨 — 사용자 알림 트리거 (#25)
   */
  public boolean collectFailed(Instant at) {
    this.failCount++;
    this.lastCollectedAt = at;
    if (this.failCount >= 3) {
      this.status = "error";
      return true;
    }
    return false;
  }

  public void retryRequested() {
    this.failCount = 0;
    this.status = "active";
    this.isActive = true;
  }
}
