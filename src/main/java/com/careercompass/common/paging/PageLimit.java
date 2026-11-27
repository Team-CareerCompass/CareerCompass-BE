package com.careercompass.common.paging;

import com.careercompass.common.api.ErrorCode;
import com.careercompass.common.exception.BusinessException;

/** limit 파라미터 공통 처리 — 기본 20, 상한 50 (초과는 50 으로 자름), 1 미만은 거부. */
public final class PageLimit {

  public static final int DEFAULT = 20;
  public static final int MAX = 50;

  private PageLimit() {}

  public static int resolve(Integer limit) {
    if (limit == null) {
      return DEFAULT;
    }
    if (limit < 1) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "limit 은 1 이상이어야 합니다", "limit");
    }
    return Math.min(limit, MAX);
  }
}
