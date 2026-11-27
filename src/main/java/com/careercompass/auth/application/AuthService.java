package com.careercompass.auth.application;

import com.careercompass.auth.domain.RefreshToken;
import com.careercompass.auth.domain.RefreshTokenRepository;
import com.careercompass.auth.domain.UserDevice;
import com.careercompass.auth.domain.UserDeviceRepository;
import com.careercompass.auth.jwt.JwtProperties;
import com.careercompass.auth.jwt.JwtProvider;
import com.careercompass.auth.oauth.OAuthClients;
import com.careercompass.auth.oauth.OAuthProfile;
import com.careercompass.common.api.ErrorCode;
import com.careercompass.common.exception.BusinessException;
import com.careercompass.user.domain.User;
import com.careercompass.user.domain.UserProfile;
import com.careercompass.user.domain.UserProfileRepository;
import com.careercompass.user.domain.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 인증 흐름 — 정책(#11, #12 에서 확정):
 *
 * <ul>
 *   <li>리프레시는 매번 회전한다. 소비된 토큰이 유예(grace) 안에 다시 오면 네트워크 경합으로 보고 새 쌍을 내준다.
 *   <li>유예 밖 재사용은 도난 신호 — 그 계보(familyId, 즉 해당 기기 세션)를 전부 폐기한다. 다른 기기는 끊지 않는다.
 *   <li>로그아웃 범위는 「이 기기」. 액세스 토큰은 블랙리스트 없이 만료까지 유효하다.
 * </ul>
 */
@Service
public class AuthService {

  private final OAuthClients oauthClients;
  private final UserRepository userRepository;
  private final UserProfileRepository profileRepository;
  private final UserDeviceRepository deviceRepository;
  private final RefreshTokenRepository refreshTokenRepository;
  private final JwtProvider jwtProvider;
  private final JwtProperties jwtProperties;
  private final TokenGenerator tokenGenerator;
  private final Clock clock;

  public AuthService(
      OAuthClients oauthClients,
      UserRepository userRepository,
      UserProfileRepository profileRepository,
      UserDeviceRepository deviceRepository,
      RefreshTokenRepository refreshTokenRepository,
      JwtProvider jwtProvider,
      JwtProperties jwtProperties,
      TokenGenerator tokenGenerator,
      Clock clock) {
    this.oauthClients = oauthClients;
    this.userRepository = userRepository;
    this.profileRepository = profileRepository;
    this.deviceRepository = deviceRepository;
    this.refreshTokenRepository = refreshTokenRepository;
    this.jwtProvider = jwtProvider;
    this.jwtProperties = jwtProperties;
    this.tokenGenerator = tokenGenerator;
    this.clock = clock;
  }

  public record TokenPair(String accessToken, String refreshToken) {}

  public record LoginResult(
      String accessToken,
      String refreshToken,
      Long userId,
      String email,
      String name,
      boolean onboardingDone,
      boolean created) {

    /** FE 가 온보딩과 피드를 가르는 값 — 처음 가입했거나 온보딩을 마치지 않았으면 true. */
    public boolean isNewUser() {
      return created || !onboardingDone;
    }
  }

  /** access 토큰 수명(초) — FE 가 만료 60초 전 미리 갱신하는 기준(expiresIn). */
  public long accessTokenTtlSeconds() {
    return jwtProperties.accessTtl().toSeconds();
  }

  @Transactional
  public LoginResult socialLogin(
      String provider, String providerAccessToken, String deviceId, String fcmToken) {
    OAuthProfile profile = oauthClients.resolve(provider).fetch(providerAccessToken);
    Instant now = clock.instant();

    java.util.Optional<User> existing =
        userRepository.findByProviderAndProviderId(provider, profile.providerId());
    boolean created = existing.isEmpty();
    User user =
        existing.orElseGet(
            () ->
                userRepository.save(
                    new User(provider, profile.providerId(), profile.email(), profile.name())));
    UserProfile userProfile =
        profileRepository
            .findById(user.id())
            .orElseGet(() -> profileRepository.save(new UserProfile(user.id())));

    UserDevice device =
        deviceRepository
            .findByUserIdAndDeviceId(user.id(), deviceId)
            .orElseGet(() -> deviceRepository.save(new UserDevice(user.id(), deviceId)));
    device.loggedIn(fcmToken, now);

    // 재로그인은 새 세션 — 이 기기의 이전 리프레시 계보를 정리한다
    refreshTokenRepository.revokeAllForDevice(user.id(), deviceId, now);
    TokenPair pair = issue(user.id(), deviceId, UUID.randomUUID(), now);

    return new LoginResult(
        pair.accessToken(),
        pair.refreshToken(),
        user.id(),
        user.email(),
        user.name(),
        userProfile.onboardingDone(),
        created);
  }

  // 도난 감지의 계보 폐기는 401 을 던져도 반드시 커밋되어야 한다 — 롤백되면 탈취자가 계속 쓴다
  @Transactional(noRollbackFor = BusinessException.class)
  public TokenPair refresh(String refreshToken) {
    Instant now = clock.instant();
    RefreshToken stored =
        refreshTokenRepository
            .findByTokenHash(tokenGenerator.hash(refreshToken))
            .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_INVALID, "알 수 없는 리프레시 토큰입니다"));

    if (stored.revoked()) {
      // 폐기된 토큰의 재사용 — 계보 전체를 다시 폐기 (멱등)
      refreshTokenRepository.revokeFamily(stored.familyId(), now);
      throw new BusinessException(ErrorCode.AUTH_INVALID, "세션이 만료되었습니다. 다시 로그인해 주세요");
    }
    if (stored.expired(now)) {
      throw new BusinessException(ErrorCode.AUTH_INVALID, "세션이 만료되었습니다. 다시 로그인해 주세요");
    }
    if (stored.rotated()) {
      boolean withinGrace = !now.isAfter(stored.rotatedAt().plus(jwtProperties.refreshGrace()));
      if (!withinGrace) {
        // 유예 밖 재사용 = 도난 신호 → 이 기기 세션 전체 폐기 (#11)
        refreshTokenRepository.revokeFamily(stored.familyId(), now);
        throw new BusinessException(ErrorCode.AUTH_INVALID, "세션이 만료되었습니다. 다시 로그인해 주세요");
      }
      // 네트워크 경합 유예 — 도난으로 보지 않고 새 쌍을 내준다
      return issue(stored.userId(), stored.deviceId(), stored.familyId(), now);
    }

    stored.markRotated(now);
    return issue(stored.userId(), stored.deviceId(), stored.familyId(), now);
  }

  /**
   * 리프레시 토큰으로 로그아웃 — FE 는 본문에 refreshToken 만 싣는다. 토큰이 가리키는 기기의 세션을 끊는다. 모르는 토큰·남의 토큰이면 아무 일도 하지 않고
   * 성공한다(멱등, #12).
   */
  @Transactional
  public void logoutByRefreshToken(Long userId, String refreshToken) {
    refreshTokenRepository
        .findByTokenHash(tokenGenerator.hash(refreshToken))
        .filter(stored -> stored.userId().equals(userId))
        .ifPresent(stored -> logout(userId, stored.deviceId()));
  }

  @Transactional
  public void logout(Long userId, String deviceId) {
    Instant now = clock.instant();
    refreshTokenRepository.revokeAllForDevice(userId, deviceId, now);
    deviceRepository.findByUserIdAndDeviceId(userId, deviceId).ifPresent(UserDevice::loggedOut);
    // 이미 로그아웃된 상태여도 멱등하게 성공 (#12)
  }

  @Transactional
  public void registerBiometric(Long userId, String deviceId) {
    UserDevice device =
        deviceRepository
            .findByUserIdAndDeviceId(userId, deviceId)
            .orElseGet(() -> deviceRepository.save(new UserDevice(userId, deviceId)));
    // (user, device) 단위 행이라 같은 기기의 다른 계정 등록을 덮지 않는다 (#13)
    device.enableBiometric();
  }

  private TokenPair issue(Long userId, String deviceId, UUID familyId, Instant now) {
    String access = jwtProvider.createAccessToken(userId);
    String refresh = tokenGenerator.newRefreshToken();
    refreshTokenRepository.save(
        new RefreshToken(
            userId,
            deviceId,
            tokenGenerator.hash(refresh),
            familyId,
            now,
            now.plus(jwtProperties.refreshTtl())));
    return new TokenPair(access, refresh);
  }
}
