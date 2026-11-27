package com.careercompass.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_tag")
public class UserTag {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false, updatable = false)
  private Long userId;

  @Column(nullable = false)
  private String tag;

  protected UserTag() {}

  public UserTag(Long userId, String tag) {
    this.userId = userId;
    this.tag = tag;
  }

  public String tag() {
    return tag;
  }
}
