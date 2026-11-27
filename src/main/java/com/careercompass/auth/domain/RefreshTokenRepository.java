package com.careercompass.auth.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

  Optional<RefreshToken> findByTokenHash(String tokenHash);

  @Modifying
  @Query(
      "update RefreshToken t set t.revokedAt = :at"
          + " where t.familyId = :familyId and t.revokedAt is null")
  int revokeFamily(@Param("familyId") UUID familyId, @Param("at") Instant at);

  @Modifying
  @Query(
      "update RefreshToken t set t.revokedAt = :at"
          + " where t.userId = :userId and t.deviceId = :deviceId and t.revokedAt is null")
  int revokeAllForDevice(
      @Param("userId") Long userId, @Param("deviceId") String deviceId, @Param("at") Instant at);
}
