package com.careercompass.experience.api;

import com.careercompass.common.api.ApiResponse;
import com.careercompass.common.paging.CursorPage;
import com.careercompass.common.paging.PageLimit;
import com.careercompass.common.security.CurrentUserId;
import com.careercompass.experience.application.ExperienceService;
import com.careercompass.experience.domain.ExperienceCard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "3. 경험 카드", description = "API_SPEC §3 — 유형 5종 CRUD, 30개 상한")
@RestController
@RequestMapping("/experiences")
public class ExperienceController {

  private final ExperienceService experienceService;

  public ExperienceController(ExperienceService experienceService) {
    this.experienceService = experienceService;
  }

  public record CardResponse(
      Long id,
      String type,
      String title,
      LocalDate startDate,
      LocalDate endDate,
      Map<String, Object> data,
      Instant createdAt) {

    static CardResponse from(ExperienceCard card) {
      return new CardResponse(
          card.id(),
          card.type(),
          card.title(),
          card.startDate(),
          card.endDate(),
          card.data(),
          card.createdAt());
    }
  }

  public record ListResponse(List<CardResponse> experiences, String nextCursor) {}

  @Operation(summary = "경험 카드 목록", description = "최신 등록순, 커서 페이징.")
  @GetMapping
  public ApiResponse<ListResponse> list(
      @CurrentUserId Long userId,
      @RequestParam(required = false) String type,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    CursorPage<ExperienceCard> page =
        experienceService.list(userId, type, cursor, PageLimit.resolve(limit));
    return ApiResponse.success(
        new ListResponse(
            page.items().stream().map(CardResponse::from).toList(), page.nextCursor()));
  }

  public record UpsertRequest(
      String type, String title, LocalDate startDate, LocalDate endDate, Map<String, Object> data) {

    ExperienceService.UpsertCard toCommand() {
      return new ExperienceService.UpsertCard(type, title, startDate, endDate, data);
    }
  }

  @Operation(summary = "경험 카드 생성", description = "유형별 필수 필드 검증. 31번째부터 LIMIT_EXCEEDED(422).")
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ApiResponse<CardResponse> create(
      @CurrentUserId Long userId, @RequestBody UpsertRequest request) {
    return ApiResponse.success(
        CardResponse.from(experienceService.create(userId, request.toCommand())));
  }

  @Operation(summary = "경험 카드 수정", description = "type 은 변경 불가.")
  @PatchMapping("/{id}")
  public ApiResponse<CardResponse> patch(
      @CurrentUserId Long userId, @PathVariable Long id, @RequestBody UpsertRequest request) {
    return ApiResponse.success(
        CardResponse.from(experienceService.patch(userId, id, request.toCommand())));
  }

  @Operation(summary = "경험 카드 삭제")
  @DeleteMapping("/{id}")
  public ApiResponse<Void> delete(@CurrentUserId Long userId, @PathVariable Long id) {
    experienceService.delete(userId, id);
    return ApiResponse.success(null);
  }
}
