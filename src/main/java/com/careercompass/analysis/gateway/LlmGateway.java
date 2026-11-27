package com.careercompass.analysis.gateway;

import java.time.LocalDate;
import java.util.List;

/**
 * LLM 작업 경계 (#35) — 파싱(#31)·코멘트(#33)·초안 생성(#36)이 전부 이 문을 지난다. 기본 구현은 온프레미스 휴리스틱({@link
 * HeuristicLlmGateway}) — 외부 전송 없음. CareerCompass-AI 서버가 서면 {@code app.ai.base-url} 설정으로 {@link
 * HttpLlmGateway} 가 대신 활성화된다(경계 확정: AI#1).
 *
 * <p>구현 계약: 장애·rate limit 은 {@code BusinessException(LLM_UNAVAILABLE)} 로 던진다. 파싱 자체의 실패(키워드 3개 미만
 * 등)는 {@link ParsingFailedException} 으로 구분한다 — 앞은 재시도 대상, 뒤는 failed 확정.
 */
public interface LlmGateway {

  class ParsingFailedException extends RuntimeException {

    /** AI 계약 §1.3 — 공고가 아닌 글(학사공지 등). 「분석 실패」가 아니라 목록에서 뺀다 (#65). */
    public static final String NOT_A_POSTING = "NOT_A_POSTING";

    private final String reasonCode; // NO_KEYWORDS | IMAGE_ONLY | EMPTY | NOT_A_POSTING | null

    public ParsingFailedException(String message) {
      this(message, null);
    }

    public ParsingFailedException(String message, String reasonCode) {
      super(message);
      this.reasonCode = reasonCode;
    }

    public String reasonCode() {
      return reasonCode;
    }

    public boolean notAPosting() {
      return NOT_A_POSTING.equals(reasonCode);
    }
  }

  record FormQuestion(int order, String question, Integer maxChars) {}

  record ParsedPosting(
      String type, // scholarship|contest|recruit|activity|other, 재분류 실패 시 null(기존 유지)
      List<String> keywords, // 최대 10, 3개 미만이면 ParsingFailedException
      String qualificationYear, // 예: "2학년 이상", 없으면 null
      String qualificationGpa, // 예: "3.0 이상"
      String qualificationMajor,
      List<String> preferences,
      LocalDate dueDate, // 「상시」·미기재는 null
      List<FormQuestion> formQuestions) {}

  ParsedPosting parsePosting(String title, String rawContent);

  record CommentContext(
      List<String> matchedKeywords,
      List<String> missingQualifications,
      List<String> matchedPreferences,
      String topExperienceTitle) {}

  record Comments(String strength, String weakness) {}

  Comments comments(CommentContext context);

  /** 분야 유사도 대상 공고 — AI 계약 §5 의 postings[] 한 건. */
  record FieldPosting(Long id, String title, List<String> keywords) {}

  /**
   * 분야 유사도 점수 (AI 계약 §5 POST /v1/field-similarity) — 공고 id → 0~100. 적합도 4축 중 field_similarity 하나만
   * 대신한다. 비어 있거나 일부 id 가 빠진 결과는 「글자 비교로 대신하라」는 뜻이다. 이 메서드는 장애를 던지지 않는다(점수 산출을 막지 않는다). 휴리스틱 구현은 늘 빈
   * 결과를 돌려준다.
   */
  default java.util.Map<Long, Integer> fieldScores(
      List<String> interestTexts, List<FieldPosting> postings) {
    return java.util.Map.of();
  }

  /** 지원서 항목 초안 (M6 #36). experiences 는 매칭도 순으로 정렬되어 온다. */
  String draftAnswer(
      String question,
      Integer maxChars,
      String tone,
      String postingTitle,
      List<String> keywords,
      List<String> experienceSummaries);
}
