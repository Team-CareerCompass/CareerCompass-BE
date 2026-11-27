package com.careercompass.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_job_interest")
public class UserJobInterest {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false, updatable = false)
  private Long userId;

  @Column(nullable = false)
  private String code;

  @Column(nullable = false)
  private int priority;

  protected UserJobInterest() {}

  public UserJobInterest(Long userId, String code, int priority) {
    this.userId = userId;
    this.code = code;
    this.priority = priority;
  }

  public String code() {
    return code;
  }

  public int priority() {
    return priority;
  }
}
