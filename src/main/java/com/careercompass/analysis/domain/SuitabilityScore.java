package com.careercompass.analysis.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 사용자×공고 적합도 (F3-2). 산출은 M5(#32)에서 채워지고, M4 는 조회만 한다. */
@Entity
@Table(name = "suitability_score")
public class SuitabilityScore {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false, updatable = false)
  private Long userId;

  @Column(name = "posting_id", nullable = false, updatable = false)
  private Long postingId;

  @Column(nullable = false)
  private int score;

  @Column(nullable = false)
  private String label;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false)
  private List<Map<String, Object>> breakdown;

  @Column(name = "strength_comment")
  private String strengthComment;

  @Column(name = "weakness_comment")
  private String weaknessComment;

  @Column(name = "computed_at", nullable = false)
  private Instant computedAt;

  protected SuitabilityScore() {}

  public SuitabilityScore(
      Long userId,
      Long postingId,
      int score,
      String label,
      List<Map<String, Object>> breakdown,
      Instant computedAt) {
    this.userId = userId;
    this.postingId = postingId;
    this.score = score;
    this.label = label;
    this.breakdown = breakdown;
    this.computedAt = computedAt;
  }

  public Long userId() {
    return userId;
  }

  public Long postingId() {
    return postingId;
  }

  public int score() {
    return score;
  }

  public String label() {
    return label;
  }

  public List<Map<String, Object>> breakdown() {
    return breakdown;
  }

  public String strengthComment() {
    return strengthComment;
  }

  public String weaknessComment() {
    return weaknessComment;
  }

  public Instant computedAt() {
    return computedAt;
  }

  public void updateScore(
      int score, String label, List<Map<String, Object>> breakdown, Instant at) {
    this.score = score;
    this.label = label;
    this.breakdown = breakdown;
    this.computedAt = at;
  }

  public void updateComments(String strength, String weakness) {
    this.strengthComment = strength;
    this.weaknessComment = weakness;
  }
}
