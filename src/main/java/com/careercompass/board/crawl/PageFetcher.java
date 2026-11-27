package com.careercompass.board.crawl;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

/**
 * 수집 전용 HTTP 클라이언트 (#26) — 리다이렉트는 수동으로 따라가며 홉마다 SSRF 검증, 도메인별 직렬화 + 최소 간격, 응답 크기 상한, 식별 UA. 문자
 * 인코딩(EUC-KR 포함)은 Jsoup 의 meta 감지에 맡긴다.
 */
@Component
public class PageFetcher {

  public record FetchedPage(URI finalUri, int status, Document document) {}

  public static class FetchException extends RuntimeException {
    public enum Kind {
      BLOCKED, // 403·405·429
      NOT_FOUND, // 404 등 4xx
      SERVER_ERROR, // 5xx
      TIMEOUT,
      NETWORK,
      TOO_LARGE,
      TOO_MANY_REDIRECTS
    }

    private final Kind kind;

    public FetchException(Kind kind, String message, Throwable cause) {
      super(message, cause);
      this.kind = kind;
    }

    public Kind kind() {
      return kind;
    }
  }

  private static final int MAX_REDIRECTS = 3;

  private final CrawlProperties properties;
  private final UrlGuard urlGuard;
  private final HttpClient httpClient;
  private final Map<String, ReentrantLock> domainLocks = new ConcurrentHashMap<>();
  private final Map<String, Long> lastRequestAt = new ConcurrentHashMap<>();

  public PageFetcher(CrawlProperties properties, UrlGuard urlGuard) {
    this.properties = properties;
    this.urlGuard = urlGuard;
    this.httpClient =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1) // 평문 http 에 h2c 업그레이드를 붙이지 않는다(구형 서버 호환)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(properties.connectTimeout())
            .build();
  }

  public FetchedPage fetch(URI uri) {
    String domain = uri.getHost().toLowerCase(Locale.ROOT);
    ReentrantLock lock = domainLocks.computeIfAbsent(domain, d -> new ReentrantLock());
    lock.lock();
    try {
      throttle(domain);
      return fetchFollowingRedirects(uri, 0);
    } finally {
      lock.unlock();
    }
  }

  private void throttle(String domain) {
    Long last = lastRequestAt.get(domain);
    if (last != null) {
      long waitMs = properties.minDomainIntervalMs() - (System.currentTimeMillis() - last);
      if (waitMs > 0) {
        try {
          Thread.sleep(waitMs);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }
    lastRequestAt.put(domain, System.currentTimeMillis());
  }

  private FetchedPage fetchFollowingRedirects(URI uri, int hop) {
    if (hop > MAX_REDIRECTS) {
      throw new FetchException(
          FetchException.Kind.TOO_MANY_REDIRECTS, "리다이렉트가 너무 많습니다: " + uri, null);
    }
    urlGuard.ensurePublicAddress(uri);

    HttpResponse<byte[]> response;
    try {
      HttpRequest request =
          HttpRequest.newBuilder(uri)
              .header("User-Agent", properties.userAgent())
              .header("Accept", "text/html,application/xhtml+xml")
              .timeout(properties.readTimeout())
              .GET()
              .build();
      response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
    } catch (java.net.http.HttpTimeoutException e) {
      throw new FetchException(FetchException.Kind.TIMEOUT, "응답 시간 초과: " + uri, e);
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new FetchException(FetchException.Kind.NETWORK, "네트워크 오류: " + uri, e);
    }

    int status = response.statusCode();
    if (status >= 300 && status < 400) {
      String location = response.headers().firstValue("Location").orElse(null);
      if (location == null) {
        throw new FetchException(FetchException.Kind.NETWORK, "Location 없는 리다이렉트", null);
      }
      return fetchFollowingRedirects(uri.resolve(location), hop + 1);
    }
    if (status == 403 || status == 405 || status == 429) {
      throw new FetchException(FetchException.Kind.BLOCKED, "접근 차단(" + status + "): " + uri, null);
    }
    if (status >= 500) {
      throw new FetchException(
          FetchException.Kind.SERVER_ERROR, "서버 오류(" + status + "): " + uri, null);
    }
    if (status >= 400) {
      throw new FetchException(
          FetchException.Kind.NOT_FOUND, "요청 실패(" + status + "): " + uri, null);
    }

    byte[] body = response.body();
    if (body.length > properties.maxBodyBytes()) {
      throw new FetchException(
          FetchException.Kind.TOO_LARGE, "응답이 너무 큽니다(" + body.length + "B): " + uri, null);
    }

    try {
      // charset null → Jsoup 이 BOM·meta 태그로 감지 (EUC-KR 게시판 대응)
      Document document = Jsoup.parse(new ByteArrayInputStream(body), null, uri.toString());
      return new FetchedPage(uri, status, document);
    } catch (IOException e) {
      throw new FetchException(FetchException.Kind.NETWORK, "HTML 파싱 실패: " + uri, e);
    }
  }
}
