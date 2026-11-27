package com.careercompass.user.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserJobInterestRepository extends JpaRepository<UserJobInterest, Long> {

  List<UserJobInterest> findAllByUserIdOrderByPriority(Long userId);

  // 즉시 실행 벌크 삭제 — 교체 저장 시 unique(user_id, code) 와의 flush 순서 충돌 방지
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("delete from UserJobInterest i where i.userId = :userId")
  void deleteByUserId(@Param("userId") Long userId);
}
