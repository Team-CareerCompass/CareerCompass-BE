package com.careercompass.pastapplication.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Set;

/** 자소서에서 분리·분류된 항목. 분류 어휘는 API_SPEC §4 (F1-4) — issue #20. */
@Entity
@Table(name = "past_application_item")
public class PastApplicationItem {

  public static final Set<String> CATEGORIES =
      Set.of("motivation", "background", "experience", "competency", "aspiration", "other");

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "past_application_id", nullable = false, updatable = false)
  private Long pastApplicationId;

  @Column(nullable = false)
  private String category;

  @Column(nullable = false)
  private String content;

  @Column(nullable = false)
  private boolean confident;

  @Column(name = "item_order", nullable = false)
  private int itemOrder;

  protected PastApplicationItem() {}

  public PastApplicationItem(
      Long pastApplicationId, String category, String content, boolean confident, int itemOrder) {
    this.pastApplicationId = pastApplicationId;
    this.category = category;
    this.content = content;
    this.confident = confident;
    this.itemOrder = itemOrder;
  }

  public Long id() {
    return id;
  }

  public Long pastApplicationId() {
    return pastApplicationId;
  }

  public String category() {
    return category;
  }

  public String content() {
    return content;
  }

  public boolean confident() {
    return confident;
  }

  public int itemOrder() {
    return itemOrder;
  }

  /** 사용자 수동 조정 — 조정된 항목은 확신 있는 것으로 본다. */
  public void recategorize(String category) {
    this.category = category;
    this.confident = true;
  }
}
