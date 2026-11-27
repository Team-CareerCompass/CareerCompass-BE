package com.careercompass.pastapplication.application;

import java.util.List;

/**
 * 자소서 항목 분리·분류 경계 (#20). 현재 기본 구현은 온프레미스 휴리스틱({@link HeuristicItemClassifier}) — 자소서 원문(PII)을 외부로
 * 보내지 않는다. LLM 분류는 CareerCompass-AI 저장소가 맡으며, 경계 확정(AI#1) 후 HTTP 게이트웨이 구현으로 교체한다(#48 개인정보 결정과 함께).
 */
public interface ItemClassifier {

  record ClassifiedItem(String category, String content, boolean confident) {}

  List<ClassifiedItem> classify(String text);
}
