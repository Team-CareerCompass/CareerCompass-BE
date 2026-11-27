package com.careercompass.pastapplication;

import static org.assertj.core.api.Assertions.assertThat;

import com.careercompass.support.ApiITBase;
import com.careercompass.support.TestFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** M2 — 과거 지원서 업로드·추출·분류(#19, #20): TXT·PDF·DOCX 3형식 실파일. */
class PastApplicationIT extends ApiITBase {

  @Autowired private JdbcTemplate jdbc;

  private static final String SAMPLE_ESSAY =
      """
      제가 이 회사에 지원하게 된 이유는 서비스에 대한 애정 때문입니다. 지원 동기를 한마디로 말하면 성장입니다.

      대학 시절 팀 프로젝트를 진행하며 백엔드 서버를 개발했습니다. 동아리 활동을 하며 협업을 배웠습니다.

      입사 후 포부는 명확합니다. 앞으로 팀에 기여하겠습니다.
      """;

  @Test
  @DisplayName("TXT 업로드 — 201, 분류 항목이 카테고리와 함께 내려온다")
  void uploadTxt() {
    Session session = login();

    ResponseEntity<String> res =
        upload(
            "/past-applications/upload",
            "2024-카카오-자소서.txt",
            SAMPLE_ESSAY.getBytes(StandardCharsets.UTF_8),
            "2024 카카오 자소서",
            session.accessToken());

    assertThat(res.getStatusCode().value()).isEqualTo(201);
    JsonNode data = json(res).path("data");
    assertThat(data.path("extractStatus").asText()).isEqualTo("done");
    assertThat(data.path("items").size()).isEqualTo(3);
    assertThat(data.path("items").toString()).contains("motivation").contains("aspiration");
  }

  @Test
  @DisplayName("PDF 업로드 — pdfbox 로 만든 실제 PDF 에서 텍스트가 추출된다")
  void uploadPdf() throws Exception {
    Session session = login();

    byte[] pdf;
    try (PDDocument document = new PDDocument()) {
      PDPage page = new PDPage();
      document.addPage(page);
      try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
        stream.beginText();
        stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        stream.newLineAtOffset(50, 700);
        stream.showText("This is my application essay written for the internship program.");
        stream.endText();
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      document.save(out);
      pdf = out.toByteArray();
    }

    ResponseEntity<String> res =
        upload("/past-applications/upload", "essay.pdf", pdf, "영문 에세이", session.accessToken());

    assertThat(res.getStatusCode().value()).isEqualTo(201);
    JsonNode data = json(res).path("data");
    assertThat(data.path("extractStatus").asText()).isEqualTo("done");
    assertThat(data.path("items").size()).isGreaterThanOrEqualTo(1);
  }

  @Test
  @DisplayName("DOCX 업로드 — poi 로 만든 실제 DOCX 에서 텍스트가 추출·분류된다")
  void uploadDocx() throws Exception {
    Session session = login();

    byte[] docx;
    try (XWPFDocument document = new XWPFDocument()) {
      document.createParagraph().createRun().setText("제가 지원하게 된 이유는 명확합니다. 지원 동기는 성장입니다.");
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      document.write(out);
      docx = out.toByteArray();
    }

    ResponseEntity<String> res =
        upload("/past-applications/upload", "자소서.docx", docx, "워드 자소서", session.accessToken());

    assertThat(res.getStatusCode().value()).isEqualTo(201);
    assertThat(json(res).path("data").path("items").get(0).path("category").asText())
        .isEqualTo("motivation");
  }

  @Test
  @DisplayName("세 형식 각각 — 업로드·목록 응답의 fileName·format 이 실제 파일과 일치한다 (#60)")
  void fileNameAndFormatInResponses() throws Exception {
    Session session = login();
    byte[] docx;
    try (XWPFDocument document = new XWPFDocument()) {
      document.createParagraph().createRun().setText("지원 동기는 성장입니다.");
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      document.write(out);
      docx = out.toByteArray();
    }
    byte[] txt = SAMPLE_ESSAY.getBytes(StandardCharsets.UTF_8);
    // 라벨에 다른 확장자를 넣어 라벨에서 형식을 읽는 우회가 틀린다는 것도 함께 확인한다
    JsonNode up =
        json(upload(
                "/past-applications/upload",
                "2024 카카오.txt",
                txt,
                "2024 카카오.pdf 지원서",
                session.accessToken()))
            .path("data");
    assertThat(up.path("fileName").asText()).isEqualTo("2024 카카오.txt");
    assertThat(up.path("format").asText()).isEqualTo("txt");
    upload("/past-applications/upload", "워드.docx", docx, "워드", session.accessToken());

    JsonNode list =
        json(call(HttpMethod.GET, "/past-applications", null, session.accessToken()))
            .path("data")
            .path("applications");
    assertThat(list).hasSize(2);
    assertThat(list.get(0).path("fileName").asText()).isEqualTo("워드.docx");
    assertThat(list.get(0).path("format").asText()).isEqualTo("docx");
    assertThat(list.get(1).path("format").asText()).isEqualTo("txt");
  }

  @Test
  @DisplayName("라벨만 고치면 항목과 사용자가 고친 카테고리가 그대로다 (#60)")
  void relabelKeepsItems() {
    Session session = login();
    JsonNode up =
        json(upload(
                "/past-applications/upload",
                "a.txt",
                SAMPLE_ESSAY.getBytes(StandardCharsets.UTF_8),
                "오타 있는 라벨",
                session.accessToken()))
            .path("data");
    long appId = up.path("id").asLong();
    long itemId = up.path("items").get(0).path("id").asLong();
    call(
        HttpMethod.PATCH,
        "/past-applications/" + appId + "/items/" + itemId,
        Map.of("category", "other"),
        session.accessToken());

    ResponseEntity<String> res =
        call(
            HttpMethod.PATCH,
            "/past-applications/" + appId,
            Map.of("label", "  고친 라벨  "),
            session.accessToken());
    assertThat(res.getStatusCode().value()).isEqualTo(200);
    JsonNode data = json(res).path("data");
    assertThat(data.path("label").asText()).isEqualTo("고친 라벨");
    assertThat(data.path("items")).hasSize(up.path("items").size());
    assertThat(data.path("items").get(0).path("category").asText()).isEqualTo("other");

    assertThat(
            call(
                    HttpMethod.PATCH,
                    "/past-applications/" + appId,
                    Map.of("label", " "),
                    session.accessToken())
                .getStatusCode()
                .value())
        .isEqualTo(400);
    Session stranger = login();
    assertThat(
            call(
                    HttpMethod.PATCH,
                    "/past-applications/" + appId,
                    Map.of("label", "남의 것"),
                    stranger.accessToken())
                .getStatusCode()
                .value())
        .isEqualTo(404);
  }

  @Test
  @DisplayName("허용되지 않는 확장자는 INVALID_INPUT(field=file)")
  void badExtensionRejected() {
    Session session = login();

    ResponseEntity<String> res =
        upload(
            "/past-applications/upload",
            "malware.exe",
            "x".getBytes(StandardCharsets.UTF_8),
            "라벨",
            session.accessToken());

    assertThat(res.getStatusCode().value()).isEqualTo(400);
    assertThat(json(res).path("error").path("field").asText()).isEqualTo("file");
  }

  @Test
  @DisplayName("11번째 업로드는 LIMIT_EXCEEDED(422)")
  void slotCap() {
    Session session = login();
    for (int i = 0; i < 10; i++) {
      TestFixtures.pastApplication(jdbc, session.userId(), "지원서 " + i);
    }

    ResponseEntity<String> res =
        upload(
            "/past-applications/upload",
            "over.txt",
            SAMPLE_ESSAY.getBytes(StandardCharsets.UTF_8),
            "초과",
            session.accessToken());

    assertThat(res.getStatusCode().value()).isEqualTo(422);
  }

  @Test
  @DisplayName("항목 카테고리 수동 조정 — 조정 후 confident=true 로 유지된다")
  void recategorize() {
    Session session = login();
    JsonNode uploaded =
        json(upload(
                "/past-applications/upload",
                "essay.txt",
                "오늘 날씨가 맑고 산책하기 좋은 하루였습니다. 커피를 마셨습니다.".getBytes(StandardCharsets.UTF_8),
                "분류 애매",
                session.accessToken()))
            .path("data");
    long appId = uploaded.path("id").asLong();
    long itemId = uploaded.path("items").get(0).path("id").asLong();
    assertThat(uploaded.path("items").get(0).path("category").asText()).isEqualTo("other");

    ResponseEntity<String> patched =
        call(
            HttpMethod.PATCH,
            "/past-applications/" + appId + "/items/" + itemId,
            Map.of("category", "growth"),
            session.accessToken());

    // 바깥 어휘는 growth, 저장은 background (v0.3 — FE 어휘에 맞춤)
    assertThat(json(patched).path("data").path("category").asText()).isEqualTo("growth");
    assertThat(
            jdbc.queryForObject(
                "select category from past_application_item where id = ?", String.class, itemId))
        .isEqualTo("background");
    assertThat(json(patched).path("data").path("confident").asBoolean()).isTrue();

    ResponseEntity<String> badCategory =
        call(
            HttpMethod.PATCH,
            "/past-applications/" + appId + "/items/" + itemId,
            Map.of("category", "unknown"),
            session.accessToken());
    assertThat(badCategory.getStatusCode().value()).isEqualTo(400);
  }

  @Test
  @DisplayName("삭제 — DB 레코드와 저장소 파일이 함께 사라진다")
  void deleteRemovesFile() {
    Session session = login();
    JsonNode uploaded =
        json(upload(
                "/past-applications/upload",
                "target.txt",
                SAMPLE_ESSAY.getBytes(StandardCharsets.UTF_8),
                "삭제 대상",
                session.accessToken()))
            .path("data");
    long appId = uploaded.path("id").asLong();
    String key = uploaded.path("filePath").asText().replace("local://", "");
    Path stored = Path.of("build/it-storage").resolve(key);
    assertThat(Files.exists(stored)).isTrue();

    ResponseEntity<String> res =
        call(HttpMethod.DELETE, "/past-applications/" + appId, null, session.accessToken());

    assertThat(res.getStatusCode().value()).isEqualTo(200);
    assertThat(Files.exists(stored)).isFalse();
    JsonNode list =
        json(call(HttpMethod.GET, "/past-applications", null, session.accessToken())).path("data");
    assertThat(list.toString()).doesNotContain("삭제 대상");
  }
}
