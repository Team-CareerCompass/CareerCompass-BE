package com.careercompass.user.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserTagRepository extends JpaRepository<UserTag, Long> {

  List<UserTag> findAllByUserIdOrderById(Long userId);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("delete from UserTag t where t.userId = :userId")
  void deleteByUserId(@Param("userId") Long userId);
}
