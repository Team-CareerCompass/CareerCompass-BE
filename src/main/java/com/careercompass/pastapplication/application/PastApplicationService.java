package com.careercompass.pastapplication.application;

import com.careercompass.common.api.ErrorCode;
import com.careercompass.common.exception.BusinessException;
import com.careercompass.common.storage.FileStorage;
import com.careercompass.pastapplication.domain.PastApplication;
import com.careercompass.pastapplication.domain.PastApplicationItem;
import com.careercompass.pastapplication.domain.PastApplicationItemRepository;
import com.careercompass.pastapplication.domain.PastApplicationRepository;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 과거 지원서 (#19, #20) — 업로드·저장·추출·분류는 업로드 요청 안에서 동기로 끝낸다(스펙 201 응답에 items 포함). */
@Service
public class PastApplicationService {

  private static final int MAX_SLOTS = 10;
  private static final long MAX_SIZE_BYTES = 10L * 1024 * 1024;
  private static final Set<String> ALLOWED_TYPES = Set.of("pdf", "docx", "txt");

  private final PastApplicationRepository applicationRepository;
  private final PastApplicationItemRepository itemRepository;
  private final FileStorage fileStorage;
  private final TextExtractor textExtractor;
  private final ItemClassifier itemClassifier;

  public PastApplicationService(
      PastApplicationRepository applicationRepository,
      PastApplicationItemRepository itemRepository,
      FileStorage fileStorage,
      TextExtractor textExtractor,
      ItemClassifier itemClassifier) {
    this.applicationRepository = applicationRepository;
    this.itemRepository = itemRepository;
    this.fileStorage = fileStorage;
    this.textExtractor = textExtractor;
    this.itemClassifier = itemClassifier;
  }

  public record UploadResult(PastApplication application, List<PastApplicationItem> items) {}

  @Transactional
  public UploadResult upload(Long userId, String originalFilename, String label, byte[] content) {
    if (applicationRepository.countByUserId(userId) >= MAX_SLOTS) {
      throw new BusinessException(
          ErrorCode.LIMIT_EXCEEDED, "과거 지원서는 최대 " + MAX_SLOTS + "개까지 보관할 수 있습니다", null);
    }
    if (label == null || label.isBlank()) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "label 은 비울 수 없습니다", "label");
    }
    if (content.length == 0) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "빈 파일은 업로드할 수 없습니다", "file");
    }
    if (content.length > MAX_SIZE_BYTES) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "파일은 10MB 이하여야 합니다", "file");
    }
    String fileType = extension(originalFilename);
    if (!ALLOWED_TYPES.contains(fileType)) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "PDF·DOCX·TXT 형식만 업로드할 수 있습니다", "file");
    }

    String key = "past-applications/" + userId + "/" + UUID.randomUUID() + "." + fileType;
    String uri = fileStorage.store(key, content);

    PastApplication application =
        applicationRepository.save(
            new PastApplication(
                userId, label.trim(), uri, fileType, baseName(originalFilename), content.length));

    List<PastApplicationItem> items = List.of();
    try {
      String text = textExtractor.extract(fileType, content);
      List<ItemClassifier.ClassifiedItem> classified = itemClassifier.classify(text);
      items =
          itemRepository.saveAll(
              java.util.stream.IntStream.range(0, classified.size())
                  .mapToObj(
                      i ->
                          new PastApplicationItem(
                              application.id(),
                              classified.get(i).category(),
                              classified.get(i).content(),
                              classified.get(i).confident(),
                              i + 1))
                  .toList());
      application.markExtracted();
    } catch (TextExtractor.ExtractionFailedException e) {
      // 추출 불가(스캔 PDF 등) — 파일은 보관하고 상태만 failed, items 는 빈 배열 (#19 계약 결정)
      application.markExtractFailed();
    }
    return new UploadResult(application, items);
  }

  @Transactional(readOnly = true)
  public List<UploadResult> list(Long userId) {
    return applicationRepository.findAllByUserIdOrderByIdDesc(userId).stream()
        .map(
            app ->
                new UploadResult(
                    app, itemRepository.findAllByPastApplicationIdOrderByItemOrder(app.id())))
        .toList();
  }

  @Transactional
  public PastApplicationItem recategorize(Long userId, Long appId, Long itemId, String category) {
    if (category == null || !PastApplicationItem.CATEGORIES.contains(category)) {
      throw new BusinessException(
          ErrorCode.INVALID_INPUT,
          "category 는 motivation/background/experience/competency/aspiration/other 중 하나여야 합니다",
          "category");
    }
    PastApplication application = owned(userId, appId);
    PastApplicationItem item =
        itemRepository
            .findByIdAndPastApplicationId(itemId, application.id())
            .orElseThrow(
                () -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "항목을 찾을 수 없습니다"));
    item.recategorize(category);
    return item;
  }

  static final int MAX_LABEL_CHARS = 100; // past_application.label varchar(100)

  /** 라벨 수정 (#60) — 항목 분류 결과와 사용자가 고친 카테고리는 그대로 둔다. */
  @Transactional
  public UploadResult relabel(Long userId, Long appId, String label) {
    if (label == null || label.isBlank()) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "label 은 비울 수 없습니다", "label");
    }
    String trimmed = label.trim();
    if (trimmed.length() > MAX_LABEL_CHARS) {
      throw new BusinessException(
          ErrorCode.INVALID_INPUT, "label 은 " + MAX_LABEL_CHARS + "자 이하여야 합니다", "label");
    }
    PastApplication application = owned(userId, appId);
    application.relabel(trimmed);
    return new UploadResult(
        application, itemRepository.findAllByPastApplicationIdOrderByItemOrder(application.id()));
  }

  @Transactional
  public void delete(Long userId, Long appId) {
    PastApplication application = owned(userId, appId);
    // 저장소 파일도 함께 삭제 (#19). URI(scheme://key)에서 key 를 되찾는다.
    String path = application.filePath();
    int schemeEnd = path.indexOf("://");
    String key = schemeEnd >= 0 ? path.substring(schemeEnd + 3) : path;
    applicationRepository.delete(application); // items 는 DB on delete cascade
    fileStorage.delete(key);
  }

  private PastApplication owned(Long userId, Long appId) {
    return applicationRepository
        .findByIdAndUserId(appId, userId)
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "지원서를 찾을 수 없습니다"));
  }

  /** 클라이언트가 경로째 보내는 경우가 있어 마지막 조각만 남긴다. 255자(컬럼 길이)로 자른다. */
  private String baseName(String filename) {
    if (filename == null || filename.isBlank()) {
      return null;
    }
    String name =
        filename.substring(Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\')) + 1);
    return name.length() > 255 ? name.substring(name.length() - 255) : name;
  }

  private String extension(String filename) {
    if (filename == null) {
      return "";
    }
    int dot = filename.lastIndexOf('.');
    return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
  }
}
