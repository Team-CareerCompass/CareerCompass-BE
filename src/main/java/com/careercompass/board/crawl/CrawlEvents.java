package com.careercompass.board.crawl;

import java.util.List;

/** 수집 결과 이벤트 — 알림(M8 #47)이 구독한다. */
public final class CrawlEvents {

  private CrawlEvents() {}

  /** 신규 공고 저장 완료. */
  public record NewPostingsCollected(Long boardId, Long userId, List<Long> postingIds) {}

  /** 3연속 실패로 게시판이 비활성화됨. */
  public record BoardDisabled(Long boardId, Long userId, String reason) {}
}
