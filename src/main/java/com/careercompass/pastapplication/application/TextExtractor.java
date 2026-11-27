package com.careercompass.pastapplication.application;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Component;

/**
 * 자소서 텍스트 추출 (#19) — 온프레미스 라이브러리만 사용, 외부 전송 없음(보고서 4.4.1 PII 경계). 스캔 PDF(텍스트 레이어 없음)는 추출 실패로 처리한다 —
 * OCR 폴백은 향후 과제.
 */
@Component
public class TextExtractor {

  public static class ExtractionFailedException extends RuntimeException {
    public ExtractionFailedException(String message, Throwable cause) {
      super(message, cause);
    }

    public ExtractionFailedException(String message) {
      super(message);
    }
  }

  public String extract(String fileType, byte[] content) {
    try {
      String text =
          switch (fileType) {
            case "pdf" -> extractPdf(content);
            case "docx" -> extractDocx(content);
            case "txt" -> new String(content, StandardCharsets.UTF_8);
            default -> throw new ExtractionFailedException("지원하지 않는 형식: " + fileType);
          };
      if (text == null || text.isBlank()) {
        throw new ExtractionFailedException("추출된 텍스트가 없습니다 (스캔 문서일 수 있음)");
      }
      return text;
    } catch (ExtractionFailedException e) {
      throw e;
    } catch (Exception e) {
      throw new ExtractionFailedException("텍스트 추출 실패", e);
    }
  }

  private String extractPdf(byte[] content) throws Exception {
    try (PDDocument document = Loader.loadPDF(content)) {
      return new PDFTextStripper().getText(document);
    }
  }

  private String extractDocx(byte[] content) throws Exception {
    try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(content))) {
      StringBuilder sb = new StringBuilder();
      for (XWPFParagraph paragraph : document.getParagraphs()) {
        sb.append(paragraph.getText()).append('\n');
      }
      return sb.toString();
    }
  }
}
