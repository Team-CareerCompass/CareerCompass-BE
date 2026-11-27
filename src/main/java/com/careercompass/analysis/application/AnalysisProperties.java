package com.careercompass.analysis.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 4축 가중치 (F3-2) — 「유저 피드백받아 가중치 개선」 요구로 상수 대신 설정값 (#32). 합은 100 이어야 한다. */
@ConfigurationProperties(prefix = "app.analysis")
public record AnalysisProperties(
    int fieldWeight,
    int qualificationWeight,
    int preferenceWeight,
    int competitionWeight,
    boolean workerEnabled) {

  public AnalysisProperties {
    if (fieldWeight + qualificationWeight + preferenceWeight + competitionWeight == 0) {
      fieldWeight = 40;
      qualificationWeight = 30;
      preferenceWeight = 20;
      competitionWeight = 10;
    }
  }

  public int weightSum() {
    return fieldWeight + qualificationWeight + preferenceWeight + competitionWeight;
  }
}
