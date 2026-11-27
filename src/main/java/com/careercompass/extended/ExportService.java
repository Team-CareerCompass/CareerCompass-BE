package com.careercompass.extended;

import com.careercompass.common.api.ErrorCode;
import com.careercompass.common.exception.BusinessException;
import com.careercompass.experience.domain.ExperienceCard;
import com.careercompass.experience.domain.ExperienceCardRepository;
import com.careercompass.user.domain.User;
import com.careercompass.user.domain.UserJobInterestRepository;
import com.careercompass.user.domain.UserProfile;
import com.careercompass.user.domain.UserProfileRepository;
import com.careercompass.user.domain.UserRepository;
import com.careercompass.user.domain.UserTag;
import com.careercompass.user.domain.UserTagRepository;
import com.careercompass.user.meta.JobCategoryRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 강점 데이터 Export (#43). notion 포맷 = Notion 호환 마크다운(붙여넣기 시 블록으로 변환됨 — 결정). summary 는 규칙 기반(결정) — LLM
 * 요약은 AI 서버 연동 후 게이트웨이로 교체 가능. 응답 크기 상한 100KB.
 */
@Service
public class ExportService {

  private static final Set<String> FORMATS = Set.of("markdown", "notion", "html", "plain");
  private static final List<String> SECTIONS =
      List.of("basic", "skills", "projects", "awards", "summary");
  private static final int MAX_CONTENT_BYTES = 100 * 1024;

  private final UserRepository userRepository;
  private final UserProfileRepository profileRepository;
  private final UserJobInterestRepository interestRepository;
  private final UserTagRepository tagRepository;
  private final JobCategoryRepository jobCategoryRepository;
  private final ExperienceCardRepository cardRepository;

  public ExportService(
      UserRepository userRepository,
      UserProfileRepository profileRepository,
      UserJobInterestRepository interestRepository,
      UserTagRepository tagRepository,
      JobCategoryRepository jobCategoryRepository,
      ExperienceCardRepository cardRepository) {
    this.userRepository = userRepository;
    this.profileRepository = profileRepository;
    this.interestRepository = interestRepository;
    this.tagRepository = tagRepository;
    this.jobCategoryRepository = jobCategoryRepository;
    this.cardRepository = cardRepository;
  }

  public record Export(String format, String content) {}

  @Transactional(readOnly = true)
  public Export export(Long userId, String format, List<String> sections) {
    if (format == null || !FORMATS.contains(format)) {
      throw new BusinessException(
          ErrorCode.INVALID_INPUT, "format 은 markdown/notion/html/plain 중 하나여야 합니다", "format");
    }
    List<String> resolvedSections = sections == null || sections.isEmpty() ? SECTIONS : sections;
    for (String section : resolvedSections) {
      if (!SECTIONS.contains(section)) {
        throw new BusinessException(
            ErrorCode.INVALID_INPUT, "알 수 없는 섹션입니다: " + section, "sections");
      }
    }

    Map<String, List<String>> body = new LinkedHashMap<>();
    User user = userRepository.findById(userId).orElseThrow();
    UserProfile profile = profileRepository.findById(userId).orElse(null);
    List<ExperienceCard> cards =
        cardRepository.findByUserIdAndDeletedAtIsNullAndIdLessThanOrderByIdDesc(
            userId, Long.MAX_VALUE, PageRequest.of(0, 30));

    for (String section : resolvedSections) {
      switch (section) {
        case "basic" -> body.put("기본 정보", basic(user, profile));
        case "skills" -> body.put("역량·관심 분야", skills(userId));
        case "projects" -> body.put("프로젝트·인턴", cardsOf(cards, Set.of("project", "intern")));
        case "awards" -> body.put("수상·자격증", cardsOf(cards, Set.of("award", "cert")));
        case "summary" -> body.put("요약", summary(user, profile, cards));
        default -> throw new IllegalStateException(section);
      }
    }

    String content =
        switch (format) {
          case "markdown", "notion" -> renderMarkdown(user, body);
          case "html" -> renderHtml(user, body);
          case "plain" -> renderPlain(user, body);
          default -> throw new IllegalStateException(format);
        };
    if (content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_CONTENT_BYTES) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Export 크기가 상한을 초과했습니다");
    }
    return new Export(format, content);
  }

  private List<String> basic(User user, UserProfile profile) {
    List<String> lines = new ArrayList<>();
    if (user.name() != null) {
      lines.add("이름: " + user.name());
    }
    if (profile != null) {
      if (profile.school() != null) {
        lines.add("학교: " + profile.school());
      }
      if (profile.department() != null) {
        lines.add("학과: " + profile.department());
      }
      if (profile.gradYear() != null) {
        lines.add("졸업 예정: " + profile.gradYear());
      }
      if (profile.gpa() != null) {
        lines.add("학점: " + profile.gpa());
      }
    }
    return lines;
  }

  private List<String> skills(Long userId) {
    List<String> lines = new ArrayList<>();
    interestRepository
        .findAllByUserIdOrderByPriority(userId)
        .forEach(
            interest ->
                jobCategoryRepository
                    .findByCode(interest.code())
                    .ifPresent(category -> lines.add("희망 직무: " + category.label())));
    List<String> tags =
        tagRepository.findAllByUserIdOrderById(userId).stream().map(UserTag::tag).toList();
    if (!tags.isEmpty()) {
      lines.add("관심 태그: " + String.join(", ", tags));
    }
    return lines;
  }

  private List<String> cardsOf(List<ExperienceCard> cards, Set<String> types) {
    List<String> lines = new ArrayList<>();
    for (ExperienceCard card : cards) {
      if (!types.contains(card.type())) {
        continue;
      }
      StringBuilder line = new StringBuilder(card.title());
      if (card.startDate() != null) {
        line.append(" (").append(card.startDate());
        line.append(card.endDate() != null ? " ~ " + card.endDate() : " ~");
        line.append(')');
      }
      Object summary = card.data() == null ? null : card.data().get("summary");
      if (summary != null) {
        line.append(" — ").append(summary);
      }
      lines.add(line.toString());
    }
    return lines;
  }

  private List<String> summary(User user, UserProfile profile, List<ExperienceCard> cards) {
    long projects = cards.stream().filter(c -> c.type().equals("project")).count();
    long interns = cards.stream().filter(c -> c.type().equals("intern")).count();
    long awards = cards.stream().filter(c -> c.type().equals("award")).count();
    StringBuilder sb = new StringBuilder();
    sb.append(profile != null && profile.department() != null ? profile.department() : "")
        .append(" 재학생으로 프로젝트 ")
        .append(projects)
        .append("건");
    if (interns > 0) {
      sb.append(", 인턴 ").append(interns).append("회");
    }
    if (awards > 0) {
      sb.append(", 수상 ").append(awards).append("건");
    }
    sb.append("의 경험을 보유하고 있습니다.");
    return List.of(sb.toString().strip());
  }

  private String renderMarkdown(User user, Map<String, List<String>> body) {
    StringBuilder sb = new StringBuilder();
    sb.append("# ").append(user.name() == null ? "내 강점 프로필" : user.name()).append("\n\n");
    body.forEach(
        (title, lines) -> {
          sb.append("## ").append(title).append("\n\n");
          for (String line : lines) {
            sb.append("- ").append(line).append('\n');
          }
          sb.append('\n');
        });
    return sb.toString();
  }

  private String renderHtml(User user, Map<String, List<String>> body) {
    StringBuilder sb = new StringBuilder();
    sb.append("<h1>")
        .append(escape(user.name() == null ? "내 강점 프로필" : user.name()))
        .append("</h1>");
    body.forEach(
        (title, lines) -> {
          sb.append("<h2>").append(escape(title)).append("</h2><ul>");
          for (String line : lines) {
            sb.append("<li>").append(escape(line)).append("</li>");
          }
          sb.append("</ul>");
        });
    return sb.toString();
  }

  private String renderPlain(User user, Map<String, List<String>> body) {
    StringBuilder sb = new StringBuilder();
    sb.append(user.name() == null ? "내 강점 프로필" : user.name()).append("\n\n");
    body.forEach(
        (title, lines) -> {
          sb.append("[").append(title).append("]\n");
          for (String line : lines) {
            sb.append("  - ").append(line).append('\n');
          }
          sb.append('\n');
        });
    return sb.toString();
  }

  private String escape(String text) {
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }
}
