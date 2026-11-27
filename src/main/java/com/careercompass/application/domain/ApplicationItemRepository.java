package com.careercompass.application.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApplicationItemRepository extends JpaRepository<ApplicationItem, Long> {

  List<ApplicationItem> findAllByApplicationIdOrderByItemOrder(Long applicationId);

  Optional<ApplicationItem> findByIdAndApplicationId(Long id, Long applicationId);

  int countByApplicationId(Long applicationId);
}
