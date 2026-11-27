package com.careercompass.user.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

  Optional<User> findByProviderAndProviderId(String provider, String providerId);

  /** 살아 있는 계정인가 — 지운 계정의 access 토큰을 401 로 떨어뜨리는 데 쓴다 (#61). */
  boolean existsByIdAndDeletedAtIsNull(Long id);
}
