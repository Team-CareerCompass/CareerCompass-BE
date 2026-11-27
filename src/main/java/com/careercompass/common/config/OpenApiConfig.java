package com.careercompass.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

  @Bean
  public OpenAPI openApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("CareerCompass API")
                .version("v0.1")
                .description("정본 계약은 docs/API_SPEC_v0.1.md — 이 문서와 다르면 스펙을 고치고 FE 에 알린다."));
  }
}
