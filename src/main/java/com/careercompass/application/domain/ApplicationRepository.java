package com.careercompass.application.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApplicationRepository extends JpaRepository<Application, Long> {

  Optional<Application> findByIdAndUserIdAndDeletedAtIsNull(Long id, Long userId);

  List<Application> findAllByUserIdAndDeletedAtIsNullOrderByIdDesc(Long userId);

  List<Application> findAllByUserIdAndStatusAndDeletedAtIsNullOrderByIdDesc(
      Long userId, String status);

  /** 지원 이력 커서 페이지 (id 내림차순). */
  List<Application> findByUserIdAndDeletedAtIsNullAndIdLessThanOrderByIdDesc(
      Long userId, Long id, org.springframework.data.domain.Pageable pageable);

  List<Application> findByUserIdAndStatusAndDeletedAtIsNullAndIdLessThanOrderByIdDesc(
      Long userId, String status, Long id, org.springframework.data.domain.Pageable pageable);

  /** 공고당 진행 중(generating·ready·partial_failed) 지원서 — #57 멱등 생성의 재사용 대상. */
  Optional<Application> findFirstByUserIdAndPostingIdAndStatusInAndDeletedAtIsNullOrderByIdDesc(
      Long userId, Long postingId, java.util.Collection<String> statuses);
}
