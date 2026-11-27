package com.careercompass.board.crawl;

import com.careercompass.common.api.ErrorCode;
import com.careercompass.common.exception.BusinessException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import org.springframework.stereotype.Component;

/**
 * SSRF 차단 (#26) — 사용자가 URL 을 직접 넣는 서비스이므로, 스킴·호스트를 검증하고 사설 대역으로의 요청을 막는다. 리다이렉트 홉마다 다시 검증해야 한다
 * ({@link PageFetcher}).
 */
@Component
public class UrlGuard {

  private final CrawlProperties properties;

  public UrlGuard(CrawlProperties properties) {
    this.properties = properties;
  }

  /** 형식·스킴 검증 후 정규화된 URI 를 돌려준다. 실패 시 INVALID_INPUT(field=url). */
  public URI validateFormat(String raw) {
    URI uri;
    try {
      uri = URI.create(raw.trim());
    } catch (Exception e) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "URL 형식이 올바르지 않습니다", "url");
    }
    String scheme = uri.getScheme();
    if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "http/https URL 만 등록할 수 있습니다", "url");
    }
    if (uri.getHost() == null) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "URL 형식이 올바르지 않습니다", "url");
    }
    return uri;
  }

  /** DNS 해석 결과가 내부 대역이면 거부한다. 리다이렉트 대상에도 호출할 것. */
  public void ensurePublicAddress(URI uri) {
    if (properties.allowPrivateAddresses()) {
      return;
    }
    try {
      for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
        if (address.isLoopbackAddress()
            || address.isAnyLocalAddress()
            || address.isSiteLocalAddress()
            || address.isLinkLocalAddress()
            || address.isMulticastAddress()) {
          throw new BusinessException(ErrorCode.INVALID_INPUT, "내부 네트워크 주소는 수집할 수 없습니다", "url");
        }
      }
    } catch (UnknownHostException e) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "호스트를 찾을 수 없습니다", "url");
    }
  }
}
