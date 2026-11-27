package com.careercompass.auth.oauth;

import com.careercompass.common.api.ErrorCode;
import com.careercompass.common.exception.BusinessException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** provider 경로 변수(kakao|google)를 실제 클라이언트로 푼다. */
@Component
public class OAuthClients {

  private final Map<String, OAuthClient> byProvider;

  public OAuthClients(List<OAuthClient> clients) {
    this.byProvider =
        clients.stream().collect(Collectors.toMap(OAuthClient::provider, Function.identity()));
  }

  public OAuthClient resolve(String provider) {
    OAuthClient client = byProvider.get(provider);
    if (client == null) {
      throw new BusinessException(
          ErrorCode.INVALID_INPUT, "지원하지 않는 소셜 로그인입니다: " + provider, "provider");
    }
    return client;
  }
}
