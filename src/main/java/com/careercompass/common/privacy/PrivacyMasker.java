package com.careercompass.common.privacy;

import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 외부 API(LLM 등)로 나가기 전 개인 식별 정보 마스킹 (#48, docs/privacy.md). 이메일·전화번호·주민등록번호 패턴을 치환한다. 이름·학교는 요청
 * 페이로드에 싣지 않는 것이 원칙이고(게이트웨이 계약), 텍스트에 섞여 들어오는 식별자는 여기서 걸러진다.
 */
@Component
public class PrivacyMasker {

  private static final Pattern EMAIL =
      Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
  private static final Pattern PHONE = Pattern.compile("01[016789][ .-]?\\d{3,4}[ .-]?\\d{4}");
  private static final Pattern RRN = Pattern.compile("\\d{6}[ -]?[1-4]\\d{6}");

  public String mask(String text) {
    if (text == null) {
      return null;
    }
    String masked = RRN.matcher(text).replaceAll("[주민번호]");
    masked = EMAIL.matcher(masked).replaceAll("[이메일]");
    masked = PHONE.matcher(masked).replaceAll("[전화번호]");
    return masked;
  }

  public List<String> maskAll(List<String> texts) {
    return texts == null ? null : texts.stream().map(this::mask).toList();
  }
}
