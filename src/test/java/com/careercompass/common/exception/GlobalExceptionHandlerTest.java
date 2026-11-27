package com.careercompass.common.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.careercompass.common.api.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerTest {

  private final MockMvc mvc =
      MockMvcBuilders.standaloneSetup(new ThrowController())
          .setControllerAdvice(new GlobalExceptionHandler())
          .setValidator(validator())
          .build();

  private static LocalValidatorFactoryBean validator() {
    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();
    return validator;
  }

  @ParameterizedTest
  @EnumSource(ErrorCode.class)
  @DisplayName("BusinessException 은 코드별 HTTP 상태와 {ok,error{code,message,field}} 본문으로 변환된다")
  void businessExceptionContract(ErrorCode code) throws Exception {
    mvc.perform(get("/throw/" + code.name()))
        .andExpect(status().is(code.status().value()))
        .andExpect(jsonPath("$.ok").value(false))
        .andExpect(jsonPath("$.error.code").value(code.name()))
        .andExpect(jsonPath("$.error.message").isNotEmpty());
  }

  @Test
  @DisplayName("@Valid 검증 실패는 INVALID_INPUT + 문제 필드명을 준다")
  void validationFailureFillsField() throws Exception {
    mvc.perform(post("/validate").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.ok").value(false))
        .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
        .andExpect(jsonPath("$.error.field").value("name"));
  }

  @Test
  @DisplayName("깨진 JSON 본문은 INVALID_INPUT 으로 접힌다")
  void unreadableBody() throws Exception {
    mvc.perform(post("/validate").contentType(MediaType.APPLICATION_JSON).content("{broken"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
  }

  @Test
  @DisplayName("예상 못 한 예외는 INTERNAL_ERROR 로 접고 내부 메시지를 본문에 노출하지 않는다")
  void unexpectedExceptionHidesDetail() throws Exception {
    mvc.perform(get("/boom"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
        .andExpect(jsonPath("$.error.message").value(ErrorCode.INTERNAL_ERROR.defaultMessage()));
  }

  @RestController
  static class ThrowController {

    record Body(@NotBlank String name) {}

    @GetMapping("/throw/{code}")
    void throwCode(@PathVariable String code) {
      throw new BusinessException(ErrorCode.valueOf(code));
    }

    @PostMapping("/validate")
    void validate(@Valid @RequestBody Body body) {}

    @GetMapping("/boom")
    void boom() {
      throw new IllegalStateException("내부 구현 세부 — 본문에 노출되면 안 된다");
    }
  }
}
