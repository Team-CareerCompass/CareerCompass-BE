package com.careercompass.analysis.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SuitabilityScoreRepository extends JpaRepository<SuitabilityScore, Long> {

  Optional<SuitabilityScore> findByUserIdAndPostingId(Long userId, Long postingId);

  void deleteAllByUserId(Long userId);
}
