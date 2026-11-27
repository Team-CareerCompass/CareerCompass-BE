package com.careercompass.board.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BoardRepository extends JpaRepository<Board, Long> {

  List<Board> findAllByUserIdOrderById(Long userId);

  Optional<Board> findByIdAndUserId(Long id, Long userId);

  boolean existsByUserIdAndUrl(Long userId, String url);

  long countByUserId(Long userId);

  List<Board> findAllByIsActiveTrueAndStatus(String status);
}
