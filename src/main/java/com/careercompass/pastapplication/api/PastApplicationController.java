package com.careercompass.pastapplication.api;

import com.careercompass.common.api.ApiResponse;
import com.careercompass.common.api.ErrorCode;
import com.careercompass.common.exception.BusinessException;
import com.careercompass.common.security.CurrentUserId;
import com.careercompass.pastapplication.application.PastApplicationService;
import com.careercompass.pastapplication.domain.PastApplicationItem;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "4. 과거 지원서", description = "API_SPEC §4 — 업로드·AI 항목 분류·수동 조정, 10개 상한")
@RestController
@RequestMapping("/past-applications")
public class PastApplicationController {

  private final PastApplicationService service;

  public PastApplicationController(PastApplicationService service) {
    this.service = service;
  }

  public record ItemResponse(Long id, String category, String content, boolean confident) {

    static ItemResponse from(PastApplicationItem item) {
      return new ItemResponse(item.id(), toWire(item.category()), item.content(), item.confident());
    }
  }

  /**
   * 「성장 배경」의 바깥 이름은 FE 어휘 {@code growth} 다(v0.3). DB·분류기는 {@code background} 를 그대로 쓰고 경계에서만 바꾼다.
   * 요청은 둘 다 받는다.
   */
  static String toWire(String category) {
    return "background".equals(category) ? "growth" : category;
  }

  static String fromWire(String category) {
    return "growth".equals(category) ? "background" : category;
  }

  /** fileName·format 은 #60 — 목록이 형식 배지를 그릴 수 있게 한다. format 은 pdf|docx|txt. */
  public record AppResponse(
      Long id,
      String label,
      String fileName,
      String format,
      String filePath,
      String extractStatus,
      List<ItemResponse> items) {

    static AppResponse from(PastApplicationService.UploadResult result) {
      return new AppResponse(
          result.application().id(),
          result.application().label(),
          result.application().fileName(),
          result.application().fileType(),
          result.application().filePath(),
          result.application().extractStatus(),
          result.items().stream().map(ItemResponse::from).toList());
    }
  }

  public record RelabelRequest(String label) {}

  @Operation(summary = "과거 지원서 라벨 수정", description = "라벨만 바꾼다. 항목과 카테고리는 그대로다 (#60).")
  @PatchMapping("/{id}")
  public ApiResponse<AppResponse> relabel(
      @CurrentUserId Long userId, @PathVariable Long id, @RequestBody RelabelRequest request) {
    return ApiResponse.success(AppResponse.from(service.relabel(userId, id, request.label())));
  }

  @Operation(
      summary = "과거 지원서 업로드",
      description =
          "PDF·DOCX·TXT, 10MB 이하. 추출·분류는 동기 처리되어 201 응답에 items 가 담긴다. 추출 실패 시 extractStatus=failed, items=[].")
  @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public ApiResponse<AppResponse> upload(
      @CurrentUserId Long userId,
      @RequestParam("file") MultipartFile file,
      @RequestParam("label") String label) {
    byte[] content;
    try {
      content = file.getBytes();
    } catch (IOException e) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "파일을 읽을 수 없습니다", "file");
    }
    return ApiResponse.success(
        AppResponse.from(service.upload(userId, file.getOriginalFilename(), label, content)));
  }

  /** FE 는 목록을 {applications: [...]} 로 읽는다. */
  public record ListResponse(List<AppResponse> applications) {}

  @Operation(summary = "내 과거 지원서 목록")
  @GetMapping
  public ApiResponse<ListResponse> list(@CurrentUserId Long userId) {
    return ApiResponse.success(
        new ListResponse(service.list(userId).stream().map(AppResponse::from).toList()));
  }

  public record RecategorizeRequest(@NotBlank String category) {}

  @Operation(summary = "항목 카테고리 수동 조정")
  @PatchMapping("/{appId}/items/{itemId}")
  public ApiResponse<ItemResponse> recategorize(
      @CurrentUserId Long userId,
      @PathVariable Long appId,
      @PathVariable Long itemId,
      @Valid @RequestBody RecategorizeRequest request) {
    return ApiResponse.success(
        ItemResponse.from(
            service.recategorize(userId, appId, itemId, fromWire(request.category()))));
  }

  @Operation(summary = "과거 지원서 삭제", description = "저장소 파일도 함께 삭제된다.")
  @DeleteMapping("/{id}")
  public ApiResponse<Void> delete(@CurrentUserId Long userId, @PathVariable Long id) {
    service.delete(userId, id);
    return ApiResponse.success(null);
  }
}
