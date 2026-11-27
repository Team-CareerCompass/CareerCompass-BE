package com.careercompass.experience.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExperienceCardRepository extends JpaRepository<ExperienceCard, Long> {

  // 최신 등록순 == id 내림차순 (IDENTITY 증가). 커서는 id 하나로 충분하다.
  List<ExperienceCard> findByUserIdAndDeletedAtIsNullAndIdLessThanOrderByIdDesc(
      Long userId, Long beforeId, Pageable pageable);

  List<ExperienceCard> findByUserIdAndTypeAndDeletedAtIsNullAndIdLessThanOrderByIdDesc(
      Long userId, String type, Long beforeId, Pageable pageable);

  Optional<ExperienceCard> findByIdAndUserIdAndDeletedAtIsNull(Long id, Long userId);

  long countByUserIdAndDeletedAtIsNull(Long userId);
}
