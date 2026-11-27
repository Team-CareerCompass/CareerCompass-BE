package com.careercompass.pastapplication.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "past_application")
public class PastApplication {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false, updatable = false)
  private Long userId;

  @Column(nullable = false)
  private String label;

  @Column(name = "file_path", nullable = false)
  private String filePath;

  @Column(name = "file_type", nullable = false)
  private String fileType;

  /** 올린 원본 파일 이름 (#60). V5 이전 행은 null. */
  @Column(name = "file_name")
  private String fileName;

  @Column(name = "file_size_bytes", nullable = false)
  private long fileSizeBytes;

  @Column(name = "extract_status", nullable = false)
  private String extractStatus;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected PastApplication() {}

  public PastApplication(
      Long userId,
      String label,
      String filePath,
      String fileType,
      String fileName,
      long fileSizeBytes) {
    this.userId = userId;
    this.label = label;
    this.filePath = filePath;
    this.fileType = fileType;
    this.fileName = fileName;
    this.fileSizeBytes = fileSizeBytes;
    this.extractStatus = "pending";
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

  public String label() {
    return label;
  }

  public String filePath() {
    return filePath;
  }

  public String fileType() {
    return fileType;
  }

  public String fileName() {
    return fileName;
  }

  /** 라벨만 고친다 — 항목·카테고리는 건드리지 않는다 (#60). */
  public void relabel(String label) {
    this.label = label;
  }

  public String extractStatus() {
    return extractStatus;
  }

  public void markExtracted() {
    this.extractStatus = "done";
  }

  public void markExtractFailed() {
    this.extractStatus = "failed";
  }
}
