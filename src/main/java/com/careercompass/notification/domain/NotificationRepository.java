package com.careercompass.notification.domain;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

  List<Notification> findByUserIdAndIdLessThanOrderByIdDesc(
      Long userId, Long beforeId, Pageable pageable);

  boolean existsByUserIdAndDedupKey(Long userId, String dedupKey);

  List<Notification> findTop100ByPushStatusOrderById(String pushStatus);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("update Notification n set n.isRead = true where n.userId = :userId and n.isRead = false")
  int markAllRead(@Param("userId") Long userId);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("delete from Notification n where n.createdAt < :threshold")
  int deleteOlderThan(@Param("threshold") Instant threshold);
}
