package com.careercompass.pastapplication.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PastApplicationItemRepository extends JpaRepository<PastApplicationItem, Long> {

  List<PastApplicationItem> findAllByPastApplicationIdOrderByItemOrder(Long pastApplicationId);

  Optional<PastApplicationItem> findByIdAndPastApplicationId(Long id, Long pastApplicationId);
}
