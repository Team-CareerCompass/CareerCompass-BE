package com.careercompass.user.api;

import com.careercompass.common.api.ApiResponse;
import com.careercompass.common.security.CurrentUserId;
import com.careercompass.user.application.AccountDeletionService;
import com.careercompass.user.application.UserProfileService;
import com.careercompass.user.application.UserProfileService.JobInterestItem;
import com.careercompass.user.application.UserProfileService.Me;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "2. 사용자 프로필", description = "API_SPEC §2 — 프로필·희망 직무·관심 태그, onboardingDone 판정")
@RestController
@RequestMapping("/users/me")
public class UserController {

  private final UserProfileService profileService;
  private final AccountDeletionService accountDeletionService;

  public UserController(
      UserProfileService profileService, AccountDeletionService accountDeletionService) {
    this.profileService = profileService;
    this.accountDeletionService = accountDeletionService;
  }

  @Operation(summary = "내 프로필 조회")
  @GetMapping
  public ApiResponse<Me> me(@CurrentUserId Long userId) {
    return ApiResponse.success(profileService.getMe(userId));
  }

  @Operation(
      summary = "계정 삭제",
      description =
          "즉시 삭제(유예 없음). 프로필·직무·태그·경험 카드·과거 지원서와 원본 파일·지원서와 이력·게시판과 공고·알림과 설정·기기(FCM)·토큰·동의 기록·소셜 연결을"
              + " 모두 지운다. 이후 그 계정의 access·refresh 토큰은 401 (#61).")
  @DeleteMapping
  public ApiResponse<Void> delete(@CurrentUserId Long userId) {
    accountDeletionService.delete(userId);
    return ApiResponse.success(null);
  }

  public record PatchMeRequest(
      @Size(max = 20, message = "이름은 20자 이하여야 합니다") String name,
      @Size(max = 50, message = "학교명은 50자 이하여야 합니다") String school,
      @Size(max = 30, message = "학과명은 30자 이하여야 합니다") String department,
      @DecimalMin(value = "0.0", message = "학점은 0.0 이상이어야 합니다") @DecimalMax(value = "4.5", message = "학점은 4.5 이하여야 합니다") BigDecimal gpa,
      @Min(value = 2000, message = "졸업연도는 2000 이상이어야 합니다") @Max(value = 2100, message = "졸업연도는 2100 이하여야 합니다") Integer gradYear) {}

  @Operation(
      summary = "내 프로필 부분 수정",
      description = "명세서 Step 1 제약: 이름 20자·학과 30자·학점 0.0~4.5·졸업연도 2000~")
  @PatchMapping
  public ApiResponse<Me> patch(
      @CurrentUserId Long userId, @Valid @RequestBody PatchMeRequest request) {
    return ApiResponse.success(
        profileService.patchMe(
            userId,
            new UserProfileService.PatchMe(
                request.name(),
                request.school(),
                request.department(),
                request.gpa(),
                request.gradYear())));
  }

  public record JobInterestRequest(@NotNull Integer priority, @NotNull String code) {}

  public record PutJobInterestsRequest(@NotNull List<JobInterestRequest> interests) {}

  @Operation(
      summary = "희망 직무 전체 교체",
      description = "1~3개. 초과·미달은 LIMIT_EXCEEDED(422), 형식 오류는 INVALID_INPUT(400).")
  @PutMapping("/job-interests")
  public ApiResponse<Me> putJobInterests(
      @CurrentUserId Long userId, @Valid @RequestBody PutJobInterestsRequest request) {
    List<JobInterestItem> interests =
        request.interests().stream().map(i -> new JobInterestItem(i.code(), i.priority())).toList();
    return ApiResponse.success(profileService.replaceJobInterests(userId, interests));
  }

  public record PutTagsRequest(@NotNull List<String> tags) {}

  @Operation(summary = "관심 태그 전체 교체", description = "정규화(공백) 후 중복 제거, 1~5개.")
  @PutMapping("/tags")
  public ApiResponse<Me> putTags(
      @CurrentUserId Long userId, @Valid @RequestBody PutTagsRequest request) {
    return ApiResponse.success(profileService.replaceTags(userId, request.tags()));
  }

  public record ConsentRequest(@NotNull Boolean modelImprovement) {}

  public record ConsentResponse(boolean modelImprovement) {}

  @Operation(
      summary = "모델 개선 활용 동의 설정",
      description = "지원 결과 데이터의 분석 모델 개선 활용·로드맵 senior 코호트 포함 동의 (F4-4, v0.2 신설). 언제든 철회 가능.")
  @PutMapping("/consent")
  public ApiResponse<ConsentResponse> putConsent(
      @CurrentUserId Long userId, @Valid @RequestBody ConsentRequest request) {
    return ApiResponse.success(
        new ConsentResponse(profileService.updateConsent(userId, request.modelImprovement())));
  }
}
