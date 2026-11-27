package com.careercompass.posting.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PostingUserStateRepository extends JpaRepository<PostingUserState, Long> {

  Optional<PostingUserState> findByUserIdAndPostingId(Long userId, Long postingId);
}
