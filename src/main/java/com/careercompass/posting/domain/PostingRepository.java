package com.careercompass.posting.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PostingRepository extends JpaRepository<Posting, Long> {

  boolean existsByBoardIdAndUrlHash(Long boardId, String urlHash);

  List<Posting> findAllByBoardIdOrderByIdDesc(Long boardId);
}
