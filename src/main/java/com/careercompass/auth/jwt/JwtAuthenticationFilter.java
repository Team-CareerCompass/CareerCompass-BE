package com.careercompass.auth.jwt;

import com.careercompass.common.api.ErrorCode;
import com.careercompass.user.domain.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bearer JWT 를 검증해 SecurityContext 에 userId 를 싣는다. 실패 시 여기서 응답하지 않고 실패 코드만 request attribute 로 남긴다
 * — 최종 401 본문은 SecurityConfig 의 entry point 가 계약 모양으로 쓴다 (#14).
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  public static final String AUTH_ERROR_ATTRIBUTE = "careercompass.auth.error";

  private final JwtProvider jwtProvider;
  private final UserRepository userRepository;

  public JwtAuthenticationFilter(JwtProvider jwtProvider, UserRepository userRepository) {
    this.jwtProvider = jwtProvider;
    this.userRepository = userRepository;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String header = request.getHeader("Authorization");
    if (header != null && header.startsWith("Bearer ")) {
      String token = header.substring("Bearer ".length()).trim();
      try {
        Long userId = jwtProvider.parseUserId(token);
        if (!userRepository.existsByIdAndDeletedAtIsNull(userId)) {
          // 서명은 맞지만 계정이 지워졌다 — access 토큰은 블랙리스트가 없으므로 여기서 막는다 (#61)
          request.setAttribute(AUTH_ERROR_ATTRIBUTE, ErrorCode.AUTH_INVALID);
          chain.doFilter(request, response);
          return;
        }
        UsernamePasswordAuthenticationToken authentication =
            new UsernamePasswordAuthenticationToken(
                userId, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
      } catch (JwtProvider.ExpiredAccessTokenException e) {
        request.setAttribute(AUTH_ERROR_ATTRIBUTE, ErrorCode.AUTH_REQUIRED);
      } catch (JwtProvider.InvalidAccessTokenException e) {
        request.setAttribute(AUTH_ERROR_ATTRIBUTE, ErrorCode.AUTH_INVALID);
      }
    }
    chain.doFilter(request, response);
  }
}
