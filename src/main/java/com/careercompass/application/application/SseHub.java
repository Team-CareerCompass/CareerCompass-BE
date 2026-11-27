package com.careercompass.application.application;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 생성 진행 SSE 허브 (#37). 구독 시 이미 끝난 항목의 스냅샷을 먼저 보내 재연결 복원을 보장하고, 생성이 끝난 지원서 구독은 스냅샷 + complete 후 즉시
 * 닫는다 — 모바일에서 끊기면 GET /applications/{id} 로도 같은 상태를 받을 수 있다.
 */
@Component
public class SseHub {

  private static final Logger log = LoggerFactory.getLogger(SseHub.class);

  /**
   * 서버 쪽 스트림 상한 — 앱의 대기 상한(스트림 전체 5분, 항목 2분)보다 짧게 둔다 (#59). 서버가 먼저 닫아야 앱이 「끊김」을 실패로 오인하지 않고 POST
   * /applications 재호출(#57)이나 단건 조회로 이어 받는다.
   */
  static final long TIMEOUT_MS = 4 * 60 * 1000L;

  public record Event(String name, Object data) {}

  private final Map<Long, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();
  private final ScheduledExecutorService closer = Executors.newSingleThreadScheduledExecutor();

  public SseEmitter subscribe(
      Long applicationId, List<Event> snapshot, boolean stillGenerating, String status) {
    SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
    for (Event event : snapshot) {
      send(emitter, event);
    }
    if (!stillGenerating) {
      // 생성 중에 끝난 경우와 같은 모양 — complete{status} (#59)
      send(emitter, new Event("complete", Map.of("status", status)));
      // 컨트롤러 반환 전에 complete 하면 컨테이너 async 설정과 경합한다 — 응답이 서고 나서 닫는다
      closer.schedule(emitter::complete, 150, TimeUnit.MILLISECONDS);
      return emitter;
    }
    CopyOnWriteArrayList<SseEmitter> list =
        emitters.computeIfAbsent(applicationId, id -> new CopyOnWriteArrayList<>());
    list.add(emitter);
    emitter.onCompletion(() -> list.remove(emitter));
    emitter.onTimeout(() -> list.remove(emitter));
    return emitter;
  }

  public void publish(Long applicationId, Event event) {
    List<SseEmitter> list = emitters.getOrDefault(applicationId, new CopyOnWriteArrayList<>());
    for (SseEmitter emitter : list) {
      send(emitter, event);
    }
  }

  public void complete(Long applicationId, String finalStatus) {
    List<SseEmitter> list = emitters.remove(applicationId);
    if (list == null) {
      return;
    }
    for (SseEmitter emitter : list) {
      send(emitter, new Event("complete", Map.of("status", finalStatus)));
      emitter.complete();
    }
  }

  /** 연결 유지 heartbeat (#37) — 프록시·모바일 네트워크의 유휴 타임아웃 방지. */
  @Scheduled(fixedDelay = 15000)
  public void heartbeat() {
    emitters.forEach(
        (applicationId, list) -> {
          for (SseEmitter emitter : list) {
            try {
              emitter.send(SseEmitter.event().comment("ping"));
            } catch (IOException | IllegalStateException e) {
              list.remove(emitter);
            }
          }
        });
  }

  private void send(SseEmitter emitter, Event event) {
    try {
      emitter.send(SseEmitter.event().name(event.name()).data(event.data()));
    } catch (IOException | IllegalStateException e) {
      log.debug("SSE 전송 실패 — 연결 제거: {}", e.toString());
    }
  }
}
