package com.careercompass.user.meta;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobCategoryRepository extends JpaRepository<JobCategory, Long> {

  List<JobCategory> findAllByOrderBySortOrder();

  Optional<JobCategory> findByCode(String code);
}
