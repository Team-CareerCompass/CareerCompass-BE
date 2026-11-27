package com.careercompass.common.events;

/** 프로필·경험 카드 변경 이벤트 — 분석(M5)이 구독해 해당 사용자의 적합도 점수를 무효화·재계산한다 (#32 재산출 정책). */
public record ProfileChanged(Long userId) {}
