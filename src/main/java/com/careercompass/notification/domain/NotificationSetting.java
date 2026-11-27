package com.careercompass.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 알림 설정 (§8, #46). 기본값: 신규·마감·수집오류 ON, 주간 리포트 OFF. 시간대는 KST 고정. */
@Entity
@Table(name = "notification_setting")
public class NotificationSetting {

  @Id
  @Column(name = "user_id")
  private Long userId;

  @Column(name = "new_posting", nullable = false)
  private boolean newPosting = true;

  @Column(name = "due_soon", nullable = false)
  private boolean dueSoon = true;

  @Column(name = "board_error", nullable = false)
  private boolean boardError = true;

  @Column(name = "weekly_report", nullable = false)
  private boolean weeklyReport = false;

  @Column(name = "quiet_start")
  private LocalTime quietStart;

  @Column(name = "quiet_end")
  private LocalTime quietEnd;

  @Column(name = "weekend_off", nullable = false)
  private boolean weekendOff = false;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "per_board", nullable = false)
  private List<Map<String, Object>> perBoard = new ArrayList<>();

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected NotificationSetting() {}

  public NotificationSetting(Long userId) {
    this.userId = userId;
  }

  @PrePersist
  @PreUpdate
  void touch() {
    this.updatedAt = Instant.now();
  }

  public boolean newPosting() {
    return newPosting;
  }

  public boolean dueSoon() {
    return dueSoon;
  }

  public boolean boardError() {
    return boardError;
  }

  public boolean weeklyReport() {
    return weeklyReport;
  }

  public LocalTime quietStart() {
    return quietStart;
  }

  public LocalTime quietEnd() {
    return quietEnd;
  }

  public boolean weekendOff() {
    return weekendOff;
  }

  public List<Map<String, Object>> perBoard() {
    return perBoard;
  }

  public boolean typeEnabled(String type) {
    return switch (type) {
      case "new_posting" -> newPosting;
      case "due_soon" -> dueSoon;
      case "board_error" -> boardError;
      case "weekly_report" -> weeklyReport;
      default -> true;
    };
  }

  /** 게시판별 ON/OFF (F2-4) — 항목이 없으면 켜진 것으로 본다. */
  public boolean boardEnabled(Long boardId) {
    if (boardId == null || perBoard == null) {
      return true;
    }
    for (Map<String, Object> entry : perBoard) {
      Object id = entry.get("boardId");
      if (id instanceof Number number && number.longValue() == boardId) {
        Object enabled = entry.get("enabled");
        return !(enabled instanceof Boolean flag) || flag;
      }
    }
    return true;
  }

  /** 방해 금지 판정 — 자정을 넘는 구간(23:00~08:00) 지원 (#46). */
  public boolean inQuietHours(LocalTime now) {
    if (quietStart == null || quietEnd == null || quietStart.equals(quietEnd)) {
      return false;
    }
    if (quietStart.isBefore(quietEnd)) {
      return !now.isBefore(quietStart) && now.isBefore(quietEnd);
    }
    return !now.isBefore(quietStart) || now.isBefore(quietEnd);
  }

  public void update(
      Boolean newPosting,
      Boolean dueSoon,
      Boolean boardError,
      Boolean weeklyReport,
      LocalTime quietStart,
      LocalTime quietEnd,
      Boolean weekendOff,
      List<Map<String, Object>> perBoard) {
    if (newPosting != null) {
      this.newPosting = newPosting;
    }
    if (dueSoon != null) {
      this.dueSoon = dueSoon;
    }
    if (boardError != null) {
      this.boardError = boardError;
    }
    if (weeklyReport != null) {
      this.weeklyReport = weeklyReport;
    }
    this.quietStart = quietStart;
    this.quietEnd = quietEnd;
    if (weekendOff != null) {
      this.weekendOff = weekendOff;
    }
    if (perBoard != null) {
      this.perBoard = perBoard;
    }
  }
}
