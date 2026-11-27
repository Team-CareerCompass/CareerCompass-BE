package com.careercompass.common.paging;

import java.util.List;

/**
 * 커서 페이로드. {@code sort} 는 정렬 축 식별자(예: collected_desc) — 축이 바뀌면 기존 커서를 거부한다. {@code keys} 는 정렬 키 값,
 * {@code id} 는 동점 방지 tie-breaker (issue #4).
 */
public record Cursor(String sort, List<Object> keys, long id) {}
