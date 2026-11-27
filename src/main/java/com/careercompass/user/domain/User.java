package com.careercompass.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "users")
public class User {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, updatable = false)
  private String provider;

  @Column(name = "provider_id", nullable = false, updatable = false)
  private String providerId;

  private String email;

  private String name;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  protected User() {}

  public User(String provider, String providerId, String email, String name) {
    this.provider = provider;
    this.providerId = providerId;
    this.email = email;
    this.name = name;
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

  public String provider() {
    return provider;
  }

  public String providerId() {
    return providerId;
  }

  public String email() {
    return email;
  }

  public String name() {
    return name;
  }

  public void rename(String name) {
    this.name = name;
  }
}
