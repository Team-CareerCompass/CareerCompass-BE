package com.careercompass.auth.oauth;

/** 소셜 사업자 API 에서 검증·조회한 사용자 정보. */
public record OAuthProfile(String providerId, String email, String name) {}
