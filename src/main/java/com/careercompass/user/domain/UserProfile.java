package com.careercompass.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "user_profile")
public class UserProfile {

  @Id
  @Column(name = "user_id")
  private Long userId;

  private String school;

  private String department;

  private BigDecimal gpa;

  @Column(name = "grad_year")
  private Integer gradYear;

  @Column(name = "onboarding_done", nullable = false)
  private boolean onboardingDone;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected UserProfile() {}

  public UserProfile(Long userId) {
    this.userId = userId;
    this.onboardingDone = false;
  }

  @PrePersist
  @PreUpdate
  void touch() {
    this.updatedAt = Instant.now();
  }

  public Long userId() {
    return userId;
  }

  public String school() {
    return school;
  }

  public String department() {
    return department;
  }

  public BigDecimal gpa() {
    return gpa;
  }

  public Integer gradYear() {
    return gradYear;
  }

  public boolean onboardingDone() {
    return onboardingDone;
  }

  public void update(String school, String department, BigDecimal gpa, Integer gradYear) {
    if (school != null) {
      this.school = school;
    }
    if (department != null) {
      this.department = department;
    }
    if (gpa != null) {
      this.gpa = gpa;
    }
    if (gradYear != null) {
      this.gradYear = gradYear;
    }
  }

  public void completeOnboarding() {
    this.onboardingDone = true;
  }
}
