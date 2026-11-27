package com.careercompass.user.meta;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "job_category")
public class JobCategory {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String code;

  private String label;

  @Column(name = "sort_order")
  private int sortOrder;

  protected JobCategory() {}

  public String code() {
    return code;
  }

  public String label() {
    return label;
  }
}
