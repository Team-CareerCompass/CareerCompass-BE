package com.careercompass.common.config;

import com.careercompass.auth.jwt.JwtAuthenticationFilter;
import com.careercompass.common.api.ApiResponse;
import com.careercompass.common.api.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 무상태 JWT 보안 설정. 인증 실패도 공통 계약 본문(ok/error)으로 나간다 — 토큰 없음/만료는 AUTH_REQUIRED, 서명 무효·형식 오류는
 * AUTH_INVALID, 권한 부족은 PERMISSION_DENIED (issue #3, #14).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

  private final ObjectMapper objectMapper;
  private final JwtAuthenticationFilter jwtAuthenticationFilter;

  public SecurityConfig(
      ObjectMapper objectMapper, JwtAuthenticationFilter jwtAuthenticationFilter) {
    this.objectMapper = objectMapper;
    this.jwtAuthenticationFilter = jwtAuthenticationFilter;
  }

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                // SSE(SseEmitter) 가 끝날 때의 ASYNC 재디스패치와 ERROR 디스패치는 인가를 다시 걸지 않는다. JWT 필터는
                // 그 디스패치에서 돌지 않아 인증이 비어 Access Denied 가 나고, 이미 커밋된 스트림이 비정상 종료돼
                // 앱(FE)은 EOF 를 「스트림 중단」으로 읽었다. 원래 요청에서 이미 인가를 마쳤다.
                auth.dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR)
                    .permitAll()
                    .requestMatchers("/actuator/health", "/error")
                    .permitAll()
                    // 로그인·재발급만 공개 — logout·biometric/register 는 인증 필요 (#12, #13)
                    .requestMatchers("/auth/social/*", "/auth/refresh")
                    .permitAll()
                    // 문서는 springdoc 프로파일 설정으로 local/dev 에서만 열린다 (issue #8)
                    .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (req, res, ex) -> {
                          Object code =
                              req.getAttribute(JwtAuthenticationFilter.AUTH_ERROR_ATTRIBUTE);
                          writeError(
                              res,
                              code instanceof ErrorCode errorCode
                                  ? errorCode
                                  : ErrorCode.AUTH_REQUIRED);
                        })
                    .accessDeniedHandler(
                        (req, res, ex) -> writeError(res, ErrorCode.PERMISSION_DENIED)));
    return http.build();
  }

  private void writeError(HttpServletResponse res, ErrorCode code) throws IOException {
    res.setStatus(code.status().value());
    res.setContentType("application/json");
    res.setCharacterEncoding(StandardCharsets.UTF_8.name());
    objectMapper.writeValue(res.getWriter(), ApiResponse.error(code));
  }
}
