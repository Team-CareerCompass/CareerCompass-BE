package com.careercompass.board.crawl;

import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

/**
 * 게시판 목록 구조 자동 감지 (#21, #22) — 같은 CSS 경로 서명을 가진 반복 링크 블록을 찾아 제목·링크·날짜 셀렉터를 뽑고, 최근 5건을 미리보기로 돌려준다.
 * 제목+링크가 나오면 성공, 날짜는 선택(없으면 마감일 파싱 정확도 저하 안내).
 */
@Component
public class BoardStructureDetector {

  public record Selectors(String row, String title, String date) {}

  public record PreviewItem(String title, String url, String date) {}

  /** status: success | login_required | spa | failed (blocked 는 fetch 단계에서 판정) */
  public record Detection(String status, Selectors selectors, List<PreviewItem> preview) {

    public static Detection failure(String status) {
      return new Detection(status, null, List.of());
    }
  }

  private static final Pattern DATE_PATTERN =
      Pattern.compile("(20\\d{2})[.\\-/]\\s?(\\d{1,2})[.\\-/]\\s?(\\d{1,2})");
  private static final int MIN_GROUP_SIZE = 5;
  private static final int MIN_TITLE_LENGTH = 6;

  public Detection detect(Document document, URI baseUri) {
    if (isLoginPage(document, baseUri)) {
      return Detection.failure("login_required");
    }

    Optional<List<Element>> best = findBestAnchorGroup(document);
    if (best.isEmpty()) {
      return Detection.failure(isSpa(document) ? "spa" : "failed");
    }

    List<Element> anchors = best.get();
    List<Element> rows = anchors.stream().map(this::findRow).toList();
    String rowSelector = signaturePath(rows.get(0), document.body());
    String titleSelector = relativeSignature(anchors.get(0), rows.get(0));
    String dateSelector = findDateSelector(rows);

    List<PreviewItem> preview = new ArrayList<>();
    for (int i = 0; i < Math.min(5, anchors.size()); i++) {
      Element anchor = anchors.get(i);
      Element row = rows.get(i);
      preview.add(
          new PreviewItem(
              anchor.text().trim(), anchor.absUrl("href"), extractDate(row).orElse(null)));
    }
    return new Detection(
        "success", new Selectors(rowSelector, titleSelector, dateSelector), preview);
  }

  /** 저장된 셀렉터로 목록 페이지에서 (제목, 절대 URL, 날짜) 를 파싱한다 — 수집기(#24)가 쓴다. */
  public List<PreviewItem> parseList(Document document, Selectors selectors) {
    List<PreviewItem> items = new ArrayList<>();
    for (Element row : document.select(selectors.row())) {
      Element anchor =
          selectors.title() == null || selectors.title().isBlank()
              ? row.selectFirst("a[href]")
              : row.selectFirst(selectors.title());
      if (anchor == null || anchor.absUrl("href").isBlank()) {
        continue;
      }
      String date = null;
      if (selectors.date() != null) {
        Element dateElement = row.selectFirst(selectors.date());
        if (dateElement != null) {
          date = normalizeDate(dateElement.text()).orElse(null);
        }
      }
      if (date == null) {
        date = extractDate(row).orElse(null);
      }
      items.add(new PreviewItem(anchor.text().trim(), anchor.absUrl("href"), date));
    }
    return items;
  }

  public static Optional<LocalDate> parseDate(String date) {
    if (date == null) {
      return Optional.empty();
    }
    Matcher matcher = DATE_PATTERN.matcher(date);
    if (!matcher.find()) {
      return Optional.empty();
    }
    try {
      return Optional.of(
          LocalDate.of(
              Integer.parseInt(matcher.group(1)),
              Integer.parseInt(matcher.group(2)),
              Integer.parseInt(matcher.group(3))));
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  private boolean isLoginPage(Document document, URI baseUri) {
    if (!document.select("input[type=password]").isEmpty()) {
      return true;
    }
    String path = baseUri.getPath() == null ? "" : baseUri.getPath().toLowerCase(Locale.ROOT);
    return path.contains("login") || path.contains("signin") || path.contains("sso");
  }

  private boolean isSpa(Document document) {
    String bodyText = document.body() == null ? "" : document.body().text();
    boolean emptyBody = bodyText.length() < 200;
    boolean hasFrameworkRoot = !document.select("#root, #app, #__next, [data-reactroot]").isEmpty();
    boolean scriptHeavy = document.select("script[src]").size() >= 3;
    return emptyBody && (hasFrameworkRoot || scriptHeavy);
  }

  private Optional<List<Element>> findBestAnchorGroup(Document document) {
    Map<String, List<Element>> groups = new LinkedHashMap<>();
    for (Element anchor : document.select("a[href]")) {
      String text = anchor.text().trim();
      if (text.length() < MIN_TITLE_LENGTH || anchor.absUrl("href").isBlank()) {
        continue;
      }
      groups
          .computeIfAbsent(signaturePath(anchor, document.body()), k -> new ArrayList<>())
          .add(anchor);
    }
    return groups.values().stream()
        .filter(list -> list.size() >= MIN_GROUP_SIZE)
        .max(Comparator.comparingInt(this::score));
  }

  private int score(List<Element> group) {
    int avgLength =
        (int) group.stream().mapToInt(a -> a.text().trim().length()).average().orElse(0);
    return group.size() * 10 + Math.min(avgLength, 40);
  }

  /** 앵커에서 위로 올라가며, 같은 서명의 형제가 그룹 크기의 절반 이상인 조상을 행(row)으로 본다. */
  private Element findRow(Element anchor) {
    Element current = anchor;
    while (current.parent() != null && !current.parent().tagName().equals("body")) {
      Element parent = current.parent();
      String signature = signature(current);
      long similarSiblings =
          parent.children().stream()
              .filter(sibling -> signature(sibling).equals(signature))
              .count();
      if (similarSiblings >= MIN_GROUP_SIZE) {
        return current;
      }
      current = parent;
    }
    return anchor;
  }

  private String findDateSelector(List<Element> rows) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (Element row : rows) {
      for (Element element : row.getAllElements()) {
        if (element.children().isEmpty() && DATE_PATTERN.matcher(element.text()).find()) {
          counts.merge(relativeSignature(element, row), 1, Integer::sum);
          break;
        }
      }
    }
    return counts.entrySet().stream()
        .filter(e -> e.getValue() >= Math.max(2, rows.size() * 6 / 10))
        .max(Map.Entry.comparingByValue())
        .map(Map.Entry::getKey)
        .orElse(null);
  }

  private Optional<String> extractDate(Element row) {
    return normalizeDate(row.text());
  }

  private Optional<String> normalizeDate(String text) {
    return parseDate(text).map(LocalDate::toString);
  }

  private String signature(Element element) {
    StringBuilder sb = new StringBuilder(element.tagName());
    element.classNames().stream()
        .filter(c -> c.matches("[A-Za-z0-9_-]+"))
        .sorted()
        .forEach(c -> sb.append('.').append(c));
    return sb.toString();
  }

  private String signaturePath(Element element, Element until) {
    List<String> parts = new ArrayList<>();
    Element current = element;
    while (current != null && current != until && !current.tagName().equals("body")) {
      parts.add(0, signature(current));
      current = current.parent();
    }
    return String.join(" > ", parts);
  }

  private String relativeSignature(Element element, Element ancestor) {
    if (element == ancestor) {
      return "";
    }
    return signaturePath(element, ancestor);
  }
}
