package com.careercompass.user.meta;

import com.careercompass.common.api.ApiResponse;
import com.careercompass.common.paging.PageLimit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** #17 — FE 로컬 상수 대체. 목록이 자주 안 바뀌므로 캐시 헤더를 붙인다. */
@Tag(name = "2-1. 메타", description = "학교·직무 목록 (온보딩 Step 1·2)")
@RestController
@RequestMapping("/meta")
public class MetaController {

  private static final CacheControl ONE_DAY = CacheControl.maxAge(1, TimeUnit.DAYS).cachePublic();

  private final SchoolRepository schoolRepository;
  private final JobCategoryRepository jobCategoryRepository;

  public MetaController(
      SchoolRepository schoolRepository, JobCategoryRepository jobCategoryRepository) {
    this.schoolRepository = schoolRepository;
    this.jobCategoryRepository = jobCategoryRepository;
  }

  public record SchoolItem(Long id, String name) {}

  public record SchoolsResponse(List<SchoolItem> schools) {}

  @Operation(summary = "학교 검색", description = "부분 일치. 검색 보조 용도라 커서 없이 limit(기본 20, 최대 50)만 지원.")
  @GetMapping("/schools")
  public ResponseEntity<ApiResponse<SchoolsResponse>> schools(
      @RequestParam(required = false, defaultValue = "") String q,
      @RequestParam(required = false) Integer limit) {
    List<SchoolItem> items =
        schoolRepository.search(q.trim(), PageRequest.of(0, PageLimit.resolve(limit))).stream()
            .map(s -> new SchoolItem(s.id(), s.name()))
            .toList();
    return ResponseEntity.ok()
        .cacheControl(ONE_DAY)
        .body(ApiResponse.success(new SchoolsResponse(items)));
  }

  public record JobCategoryItem(String code, String label) {}

  public record JobCategoriesResponse(List<JobCategoryItem> categories) {}

  @Operation(summary = "희망 직무 목록", description = "code 는 PUT /users/me/job-interests 와 동일 어휘.")
  @GetMapping("/job-categories")
  public ResponseEntity<ApiResponse<JobCategoriesResponse>> jobCategories() {
    List<JobCategoryItem> items =
        jobCategoryRepository.findAllByOrderBySortOrder().stream()
            .map(c -> new JobCategoryItem(c.code(), c.label()))
            .toList();
    return ResponseEntity.ok()
        .cacheControl(ONE_DAY)
        .body(ApiResponse.success(new JobCategoriesResponse(items)));
  }
}
