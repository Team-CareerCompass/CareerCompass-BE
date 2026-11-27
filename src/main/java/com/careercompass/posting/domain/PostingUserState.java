package com.careercompass.posting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** 사용자×공고 상태 — 읽음·북마크 (#30). unreadOnly 필터와 마감 임박 알림(M8)이 읽는다. */
@Entity
@Table(name = "posting_user_state")
public class PostingUserState {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false, updatable = false)
  private Long userId;

  @Column(name = "posting_id", nullable = false, updatable = false)
  private Long postingId;

  @Column(name = "is_read", nullable = false)
  private boolean isRead;

  @Column(name = "is_bookmarked", nullable = false)
  private boolean isBookmarked;

  @Column(name = "read_at")
  private Instant readAt;

  @Column(name = "bookmarked_at")
  private Instant bookmarkedAt;

  protected PostingUserState() {}

  public PostingUserState(Long userId, Long postingId) {
    this.userId = userId;
    this.postingId = postingId;
  }

  public boolean isRead() {
    return isRead;
  }

  public boolean isBookmarked() {
    return isBookmarked;
  }

  public void markRead(Instant at) {
    if (!this.isRead) {
      this.isRead = true;
      this.readAt = at;
    }
  }

  public void bookmark(Instant at) {
    this.isBookmarked = true;
    this.bookmarkedAt = at;
  }

  public void unbookmark() {
    this.isBookmarked = false;
    this.bookmarkedAt = null;
  }
}
