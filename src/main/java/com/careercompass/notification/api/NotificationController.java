package com.careercompass.notification.api;

import com.careercompass.common.api.ApiResponse;
import com.careercompass.common.paging.CursorPage;
import com.careercompass.common.paging.PageLimit;
import com.careercompass.common.security.CurrentUserId;
import com.careercompass.notification.application.NotificationService;
import com.careercompass.notification.domain.Notification;
import com.careercompass.notification.domain.NotificationSetting;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "8. 알림", description = "API_SPEC §8 — 알림함·설정·FCM 토큰 갱신")
@RestController
@RequestMapping("/notifications")
public class NotificationController {

  private final NotificationService service;

  public NotificationController(NotificationService service) {
    this.service = service;
  }

  public record NotificationResponse(
      Long id,
      String type,
      String title,
      String body,
      String deeplink,
      boolean isRead,
      Instant createdAt) {

    static NotificationResponse from(Notification notification) {
      return new NotificationResponse(
          notification.id(),
          notification.type(),
          notification.title(),
          notification.body(),
          notification.deeplink(),
          notification.isRead(),
          notification.createdAt());
    }
  }

  public record ListResponse(List<NotificationResponse> notifications, String nextCursor) {}

  @Operation(
      summary = "알림 목록",
      description = "최신순 커서 페이징. 딥링크 포함 — FE 의 careercompass:// 스킴 (#45).")
  @GetMapping
  public ApiResponse<ListResponse> list(
      @CurrentUserId Long userId,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    CursorPage<Notification> page = service.list(userId, cursor, PageLimit.resolve(limit));
    return ApiResponse.success(
        new ListResponse(
            page.items().stream().map(NotificationResponse::from).toList(), page.nextCursor()));
  }

  @Operation(summary = "모두 읽음")
  @PostMapping("/read-all")
  public ApiResponse<Void> readAll(@CurrentUserId Long userId) {
    service.readAll(userId);
    return ApiResponse.success(null);
  }

  public record QuietHours(String start, String end) {}

  public record SettingsPayload(
      Boolean newPosting,
      Boolean dueSoon,
      Boolean boardError,
      Boolean weeklyReport,
      QuietHours quietHours,
      Boolean weekendOff,
      List<Map<String, Object>> perBoard) {}

  public record SettingsResponse(
      boolean newPosting,
      boolean dueSoon,
      boolean boardError,
      boolean weeklyReport,
      QuietHours quietHours,
      boolean weekendOff,
      List<Map<String, Object>> perBoard) {

    static SettingsResponse from(
        NotificationSetting setting, List<Map<String, Object>> perBoardView) {
      QuietHours quiet =
          setting.quietStart() == null || setting.quietEnd() == null
              ? null
              : new QuietHours(setting.quietStart().toString(), setting.quietEnd().toString());
      return new SettingsResponse(
          setting.newPosting(),
          setting.dueSoon(),
          setting.boardError(),
          setting.weeklyReport(),
          quiet,
          setting.weekendOff(),
          perBoardView);
    }
  }

  @Operation(summary = "알림 설정 조회", description = "기본값: 신규·마감·수집오류 ON, 주간 리포트 OFF.")
  @GetMapping("/settings")
  public ApiResponse<SettingsResponse> getSettings(@CurrentUserId Long userId) {
    NotificationSetting setting = service.settingOf(userId);
    return ApiResponse.success(
        SettingsResponse.from(setting, service.perBoardView(userId, setting)));
  }

  @Operation(
      summary = "알림 설정 수정",
      description =
          "방해 금지(quietHours, KST, 자정 걸침 지원) 중 발송은 보류 후 해제 시 일괄 발송된다. 게시판별 OFF 는 신규 공고 알림에 적용.")
  @PutMapping("/settings")
  public ApiResponse<SettingsResponse> putSettings(
      @CurrentUserId Long userId, @RequestBody SettingsPayload payload) {
    LocalTime quietStart =
        payload.quietHours() == null ? null : LocalTime.parse(payload.quietHours().start());
    LocalTime quietEnd =
        payload.quietHours() == null ? null : LocalTime.parse(payload.quietHours().end());
    NotificationSetting updated =
        service.updateSetting(
            userId,
            payload.newPosting(),
            payload.dueSoon(),
            payload.boardError(),
            payload.weeklyReport(),
            quietStart,
            quietEnd,
            payload.weekendOff(),
            payload.perBoard());
    return ApiResponse.success(
        SettingsResponse.from(updated, service.perBoardView(userId, updated)));
  }

  public record FcmTokenRequest(@NotBlank String deviceId, @NotBlank String fcmToken) {}

  @Operation(summary = "FCM 토큰 갱신", description = "로그인 사이에 갱신된 토큰을 반영하는 경로 (v0.2 신설, #44).")
  @PostMapping("/fcm-token")
  public ApiResponse<Void> updateFcmToken(
      @CurrentUserId Long userId, @Valid @RequestBody FcmTokenRequest request) {
    service.updateFcmToken(userId, request.deviceId(), request.fcmToken());
    return ApiResponse.success(null);
  }
}
