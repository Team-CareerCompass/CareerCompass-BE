package com.careercompass.posting.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PostingParsedRepository extends JpaRepository<PostingParsed, Long> {

  List<PostingParsed> findTop20ByParseStatusOrderByPostingId(String parseStatus);
}
