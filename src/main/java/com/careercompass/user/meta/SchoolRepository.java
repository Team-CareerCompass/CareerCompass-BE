package com.careercompass.user.meta;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SchoolRepository extends JpaRepository<School, Long> {

  @Query(
      "select s from School s where lower(s.name) like lower(concat('%', :q, '%'))"
          + " order by s.name asc")
  List<School> search(@Param("q") String q, Pageable pageable);
}
