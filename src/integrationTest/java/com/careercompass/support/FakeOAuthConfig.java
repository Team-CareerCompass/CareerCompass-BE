package com.careercompass.support;

import com.careercompass.auth.oauth.OAuthClient;
import com.careercompass.auth.oauth.OAuthClients;
import com.careercompass.auth.oauth.OAuthProfile;
import com.careercompass.common.api.ErrorCode;
import com.careercompass.common.exception.BusinessException;
import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 외부 의존 대체 경계 (issue #9) — 소셜 사업자 API 를 실제로 부르지 않는다. 토큰 문자열이 곧 사용자 식별자가 되어 테스트마다 유니크한 사용자를 만든다.
 * "bad-" 로 시작하는 토큰은 사업자 거부를 흉내 낸다.
 */
@TestConfiguration
public class FakeOAuthConfig {

  @Bean
  @Primary
  public OAuthClients fakeOAuthClients() {
    return new OAuthClients(List.of(fake("kakao"), fake("google")));
  }

  private static OAuthClient fake(String provider) {
    return new OAuthClient() {
      @Override
      public String provider() {
        return provider;
      }

      @Override
      public OAuthProfile fetch(String accessToken) {
        if (accessToken.startsWith("bad-")) {
          throw new BusinessException(ErrorCode.AUTH_INVALID, "소셜 토큰 검증에 실패했습니다");
        }
        return new OAuthProfile(provider + "-" + accessToken, accessToken + "@test.local", "테스트유저");
      }
    };
  }
}
