package com.careercompass.extended;

import com.careercompass.common.api.ApiResponse;
import com.careercompass.common.security.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "7. 신규 기능", description = "API_SPEC §7 — For You 추천·로드맵 비교·강점 Export")
@RestController
public class ExtendedController {

  private final ForYouService forYouService;
  private final RoadmapService roadmapService;
  private final ExportService exportService;

  public ExtendedController(
      ForYouService forYouService, RoadmapService roadmapService, ExportService exportService) {
    this.forYouService = forYouService;
    this.roadmapService = roadmapService;
    this.exportService = exportService;
  }

  @Operation(
      summary = "For You 추천",
      description = "톱 픽(적합도+마감 가중)·강점 기반·취약점 보완, 카테고리별 최대 5건. 점수 산출된 공고만 대상.")
  @GetMapping("/feed/for-you")
  public ApiResponse<ForYouService.ForYou> forYou(@CurrentUserId Long userId) {
    return ApiResponse.success(forYouService.recommend(userId));
  }

  @Operation(
      summary = "커리어 로드맵 코호트 비교",
      description =
          "peer(같은 학과·학년)/senior(합격+동의 사용자)/me_only. 표본 5명 미만이면 metrics 가 비어 내려간다(개인 식별 방지).")
  @GetMapping("/roadmap/compare")
  public ApiResponse<RoadmapService.Compare> compare(
      @CurrentUserId Long userId, @RequestParam(defaultValue = "peer") String cohort) {
    return ApiResponse.success(roadmapService.compare(userId, cohort));
  }

  public record ExportRequest(@NotBlank String format, List<String> sections) {}

  @Operation(
      summary = "강점 데이터 Export",
      description = "markdown/notion(Notion 호환 마크다운)/html/plain. sections 생략 시 전체.")
  @PostMapping("/export")
  public ApiResponse<ExportService.Export> export(
      @CurrentUserId Long userId, @Valid @RequestBody ExportRequest request) {
    return ApiResponse.success(exportService.export(userId, request.format(), request.sections()));
  }
}
