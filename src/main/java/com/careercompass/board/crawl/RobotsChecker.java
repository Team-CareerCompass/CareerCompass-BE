package com.careercompass.board.crawl;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * robots.txt 준수 (#26 결정: 따른다) — User-agent: * 그룹의 Disallow 접두사 규칙을 적용한다. Allow 가 더 구체적이면 허용(단순 최장
 * 일치). 도메인별 1시간 캐시. robots.txt 를 못 가져오면 허용으로 간주한다(관행).
 */
@Component
public class RobotsChecker {

  private record Rules(List<String> disallows, List<String> allows, Instant fetchedAt) {}

  private static final Duration CACHE_TTL = Duration.ofHours(1);

  private final CrawlProperties properties;
  private final HttpClient httpClient;
  private final Map<String, Rules> cache = new ConcurrentHashMap<>();

  public RobotsChecker(CrawlProperties properties) {
    this.properties = properties;
    this.httpClient =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1) // 평문 http 에 h2c 업그레이드를 붙이지 않는다(구형 서버 호환)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(properties.connectTimeout())
            .build();
  }

  public boolean allowed(URI uri) {
    String origin =
        uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
    Rules rules = cache.get(origin);
    if (rules == null || rules.fetchedAt().plus(CACHE_TTL).isBefore(Instant.now())) {
      rules = fetchRules(origin);
      cache.put(origin, rules);
    }
    String path = uri.getPath() == null || uri.getPath().isEmpty() ? "/" : uri.getPath();

    String longestDisallow = longestPrefix(rules.disallows(), path);
    if (longestDisallow == null) {
      return true;
    }
    String longestAllow = longestPrefix(rules.allows(), path);
    return longestAllow != null && longestAllow.length() >= longestDisallow.length();
  }

  private String longestPrefix(List<String> prefixes, String path) {
    String best = null;
    for (String prefix : prefixes) {
      if (!prefix.isEmpty() && path.startsWith(prefix)) {
        if (best == null || prefix.length() > best.length()) {
          best = prefix;
        }
      }
    }
    return best;
  }

  private Rules fetchRules(String origin) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(origin + "/robots.txt"))
              .header("User-Agent", properties.userAgent())
              .timeout(properties.readTimeout())
              .GET()
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        return new Rules(List.of(), List.of(), Instant.now());
      }
      return parse(response.body());
    } catch (Exception e) {
      return new Rules(List.of(), List.of(), Instant.now());
    }
  }

  private Rules parse(String body) {
    List<String> disallows = new ArrayList<>();
    List<String> allows = new ArrayList<>();
    boolean inWildcardGroup = false;
    for (String rawLine : body.split("\n")) {
      String line = rawLine.split("#", 2)[0].trim();
      if (line.isEmpty()) {
        continue;
      }
      String lower = line.toLowerCase(Locale.ROOT);
      if (lower.startsWith("user-agent:")) {
        inWildcardGroup = line.substring("user-agent:".length()).trim().equals("*");
      } else if (inWildcardGroup && lower.startsWith("disallow:")) {
        disallows.add(line.substring("disallow:".length()).trim());
      } else if (inWildcardGroup && lower.startsWith("allow:")) {
        allows.add(line.substring("allow:".length()).trim());
      }
    }
    return new Rules(disallows, allows, Instant.now());
  }
}
