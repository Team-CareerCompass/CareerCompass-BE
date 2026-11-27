package com.careercompass.posting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 공고 구조화 결과 + 파싱 처리 상태(pending|done|failed). 상태는 공고 응답 계약에 노출된다 (#27) — FE 가 「분석 중 / 분석 실패」를 구분해
 * 그린다. 구조화 필드 채우기는 M5(#31).
 */
@Entity
@Table(name = "posting_parsed")
public class PostingParsed {

  @Id
  @Column(name = "posting_id")
  private Long postingId;

  @Column(name = "parse_status", nullable = false)
  private String parseStatus = "pending";

  @JdbcTypeCode(SqlTypes.JSON)
  private List<String> keywords;

  @JdbcTypeCode(SqlTypes.JSON)
  private Map<String, Object> qualifications;

  @JdbcTypeCode(SqlTypes.JSON)
  private List<String> preferences;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "form_questions")
  private List<Map<String, Object>> formQuestions;

  @Column(name = "parsed_at")
  private Instant parsedAt;

  protected PostingParsed() {}

  public PostingParsed(Long postingId) {
    this.postingId = postingId;
  }

  public Long postingId() {
    return postingId;
  }

  public String parseStatus() {
    return parseStatus;
  }

  public List<String> keywords() {
    return keywords;
  }

  public Map<String, Object> qualifications() {
    return qualifications;
  }

  public List<String> preferences() {
    return preferences;
  }

  public List<Map<String, Object>> formQuestions() {
    return formQuestions;
  }

  public void parsed(
      List<String> keywords,
      Map<String, Object> qualifications,
      List<String> preferences,
      List<Map<String, Object>> formQuestions) {
    this.keywords = keywords;
    this.qualifications = qualifications;
    this.preferences = preferences;
    this.formQuestions = formQuestions;
    this.parseStatus = "done";
    this.parsedAt = Instant.now();
  }

  public void parseFailed() {
    this.parseStatus = "failed";
    this.parsedAt = Instant.now();
  }

  /** 공고가 아닌 글(AI reasonCode=NOT_A_POSTING) — 목록·유사 공고·적합도 대상에서 빠진다 (#65). */
  public void parseExcluded() {
    this.parseStatus = "excluded";
    this.parsedAt = Instant.now();
  }
}
