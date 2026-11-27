package com.careercompass.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** 외부 인터넷 없이 크롤러를 검증하기 위한 픽스처 게시판 서버 (JDK 내장 HttpServer). */
public class FixtureWebServer {

  private HttpServer server;
  private final AtomicBoolean failMode = new AtomicBoolean(false);
  private final AtomicInteger postCount = new AtomicInteger(8);

  public void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/board", this::board);
    server.createContext("/posts/", this::post);
    server.createContext("/login", ex -> html(ex, 200, LOGIN_PAGE));
    server.createContext("/spa", ex -> html(ex, 200, SPA_PAGE));
    server.createContext("/blocked", ex -> html(ex, 403, "<html><body>forbidden</body></html>"));
    server.createContext("/private/board", this::board);
    server.createContext("/robots.txt", ex -> text(ex, 200, "User-agent: *\nDisallow: /private\n"));
    server.start();
  }

  public void stop() {
    if (server != null) {
      server.stop(0);
    }
  }

  public String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  public void setFailMode(boolean fail) {
    failMode.set(fail);
  }

  /** 게시글 1건 추가 — 다음 수집에서 신규로 잡힌다. */
  public int addPost() {
    return postCount.incrementAndGet();
  }

  private void board(HttpExchange exchange) throws IOException {
    if (failMode.get()) {
      html(exchange, 500, "<html><body>oops</body></html>");
      return;
    }
    StringBuilder rows = new StringBuilder();
    int total = postCount.get();
    for (int i = total; i >= 1; i--) {
      rows.append("<tr><td class=\"num\">")
          .append(i)
          .append("</td><td class=\"title\"><a href=\"/posts/")
          .append(i)
          .append("\">2026 장학금 및 채용 안내 제")
          .append(i)
          .append("호</a></td><td class=\"date\">2026.09.0")
          .append((i % 9) + 1)
          .append("</td></tr>");
    }
    html(
        exchange,
        200,
        "<html><head><meta charset=\"utf-8\"><title>공지</title></head><body>"
            + "<h1>학과 공지사항</h1><table class=\"board-list\"><tbody>"
            + rows
            + "</tbody></table></body></html>");
  }

  private void post(HttpExchange exchange) throws IOException {
    html(
        exchange,
        200,
        "<html><head><meta charset=\"utf-8\"></head><body><div class=\"view\">"
            + "장학금 공고 본문입니다. 지원 자격은 재학생입니다. 접수기간: 2026.09.01 ~ 2026.09.30 까지입니다."
            + "</div></body></html>");
  }

  private void html(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    }
  }

  private void text(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "text/plain");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    }
  }

  private static final String LOGIN_PAGE =
      "<html><body><form action=\"/login\"><input type=\"text\" name=\"id\">"
          + "<input type=\"password\" name=\"pw\"><button>로그인</button></form></body></html>";

  private static final String SPA_PAGE =
      "<html><head><script src=\"/app.js\"></script><script src=\"/vendor.js\"></script>"
          + "<script src=\"/runtime.js\"></script></head><body><div id=\"root\"></div></body></html>";
}
