package com.careercompass.experience.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 경험 카드 — 유형 5종, 유형별 세부 필드는 data(jsonb) 에 담는다 (§3, F1-3). */
@Entity
@Table(name = "experience_card")
public class ExperienceCard {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false, updatable = false)
  private Long userId;

  @Column(nullable = false, updatable = false)
  private String type;

  @Column(nullable = false)
  private String title;

  @Column(name = "start_date")
  private LocalDate startDate;

  @Column(name = "end_date")
  private LocalDate endDate;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false)
  private Map<String, Object> data = new LinkedHashMap<>();

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  protected ExperienceCard() {}

  public ExperienceCard(
      Long userId,
      String type,
      String title,
      LocalDate startDate,
      LocalDate endDate,
      Map<String, Object> data) {
    this.userId = userId;
    this.type = type;
    this.title = title;
    this.startDate = startDate;
    this.endDate = endDate;
    this.data = data == null ? new LinkedHashMap<>() : new LinkedHashMap<>(data);
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

  public String type() {
    return type;
  }

  public String title() {
    return title;
  }

  public LocalDate startDate() {
    return startDate;
  }

  public LocalDate endDate() {
    return endDate;
  }

  public Map<String, Object> data() {
    return data;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public void update(
      String title, LocalDate startDate, LocalDate endDate, Map<String, Object> data) {
    if (title != null) {
      this.title = title;
    }
    if (startDate != null) {
      this.startDate = startDate;
    }
    if (endDate != null) {
      this.endDate = endDate;
    }
    if (data != null) {
      this.data = new LinkedHashMap<>(data);
    }
  }

  public void softDelete() {
    this.deletedAt = Instant.now();
  }
}
