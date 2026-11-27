package com.careercompass.pastapplication.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PastApplicationRepository extends JpaRepository<PastApplication, Long> {

  List<PastApplication> findAllByUserIdOrderByIdDesc(Long userId);

  Optional<PastApplication> findByIdAndUserId(Long id, Long userId);

  long countByUserId(Long userId);
}
