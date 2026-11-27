package com.careercompass.auth.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserDeviceRepository extends JpaRepository<UserDevice, Long> {

  Optional<UserDevice> findByUserIdAndDeviceId(Long userId, String deviceId);

  List<UserDevice> findAllByUserId(Long userId);
}
