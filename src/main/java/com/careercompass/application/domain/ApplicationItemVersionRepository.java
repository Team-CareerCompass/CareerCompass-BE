package com.careercompass.application.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApplicationItemVersionRepository
    extends JpaRepository<ApplicationItemVersion, Long> {

  List<ApplicationItemVersion> findAllByApplicationItemIdOrderByIdAsc(Long applicationItemId);
}
