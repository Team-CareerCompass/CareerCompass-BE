package com.careercompass.notification.application;

import com.careercompass.auth.domain.UserDevice;
import com.careercompass.auth.domain.UserDeviceRepository;
import com.careercompass.common.paging.Cursor;
import com.careercompass.common.paging.CursorCodec;
import com.careercompass.common.paging.CursorPage;
import com.careercompass.notification.domain.Notification;
import com.careercompass.notification.domain.NotificationRepository;
import com.careercompass.notification.domain.NotificationSetting;
import com.careercompass.notification.domain.NotificationSettingRepository;
import com.careercompass.notification.fcm.FcmSender;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림 기록·발송 (#44~#46). 정책: 알림함 기록은 즉시, 푸시는 방해 금지·주말 OFF 시 pending 으로 **미뤄서**(막지 않음) 워커가 이후 일괄 발송한다.
 * 시간대 KST 고정. dedupKey 로 같은 사건 재발송을 막는다.
 */
@Service
public class NotificationService {

  public static final String SORT = "created_desc";
  private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
  private static final ZoneId KST = ZoneId.of("Asia/Seoul");

  private final NotificationRepository notificationRepository;
  private final NotificationSettingRepository settingRepository;
  private final UserDeviceRepository deviceRepository;
  private final FcmSender fcmSender;
  private final Clock clock;

  private final com.careercompass.board.domain.BoardRepository boardRepository;

  public NotificationService(
      NotificationRepository notificationRepository,
      NotificationSettingRepository settingRepository,
      UserDeviceRepository deviceRepository,
      FcmSender fcmSender,
      Clock clock,
      com.careercompass.board.domain.BoardRepository boardRepository) {
    this.notificationRepository = notificationRepository;
    this.settingRepository = settingRepository;
    this.deviceRepository = deviceRepository;
    this.fcmSender = fcmSender;
    this.clock = clock;
    this.boardRepository = boardRepository;
  }

  /**
   * perBoard 의 바깥 모양 (#63) — 지금 있는 내 게시판 전부를 {boardId, enabled} 로 낸다. 기록이 없으면 켜짐, 지워진 게시판은 빠진다. 그래서
   * 화면에 「모르는 boardId」가 생기지 않는다.
   */
  @Transactional(readOnly = true)
  public List<java.util.Map<String, Object>> perBoardView(
      Long userId, NotificationSetting setting) {
    List<java.util.Map<String, Object>> view = new java.util.ArrayList<>();
    for (com.careercompass.board.domain.Board board :
        boardRepository.findAllByUserIdOrderById(userId)) {
      java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>();
      entry.put("boardId", board.id());
      entry.put("enabled", setting.boardEnabled(board.id()));
      view.add(entry);
    }
    return view;
  }

  /**
   * PUT 의 perBoard 병합 (#63) — 요청에 실린 내 게시판만 바꾸고, 빠진 게시판은 직전 값을 지킨다. 남의·없는 boardId 는 버린다. 전체 교체로 두면
   * 화면이 그리지 못해 빠뜨린 게시판의 OFF 가 저장 한 번에 조용히 켜진다.
   */
  private List<java.util.Map<String, Object>> mergePerBoard(
      Long userId, NotificationSetting setting, List<java.util.Map<String, Object>> requested) {
    java.util.Map<Long, Boolean> requestedById = new java.util.HashMap<>();
    if (requested != null) {
      for (java.util.Map<String, Object> entry : requested) {
        if (entry != null
            && entry.get("boardId") instanceof Number id
            && entry.get("enabled") instanceof Boolean enabled) {
          requestedById.put(id.longValue(), enabled);
        }
      }
    }
    List<java.util.Map<String, Object>> merged = perBoardView(userId, setting);
    for (java.util.Map<String, Object> entry : merged) {
      Long id = ((Number) entry.get("boardId")).longValue();
      if (requestedById.containsKey(id)) {
        entry.put("enabled", requestedById.get(id));
      }
    }
    return merged;
  }

  /**
   * 트리거 공통 진입점 (#47) — 설정 OFF·게시판 OFF 면 기록조차 하지 않고, 방해 금지면 기록 후 푸시만 보류한다.
   *
   * @return 기록됐으면 true
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean notify(
      Long userId,
      String type,
      Long boardId,
      String title,
      String body,
      String deeplink,
      String dedupKey) {
    NotificationSetting setting = settingOf(userId);
    if (!setting.typeEnabled(type) || !setting.boardEnabled(boardId)) {
      return false;
    }
    if (notificationRepository.existsByUserIdAndDedupKey(userId, dedupKey)) {
      return false;
    }
    Notification notification;
    try {
      notification =
          notificationRepository.saveAndFlush(
              new Notification(userId, type, title, body, deeplink, dedupKey));
    } catch (DataIntegrityViolationException e) {
      return false; // 동시 트리거 경합 — dedup 유니크가 막았다
    }

    if (deferPush(setting)) {
      return true; // pending 유지 — flushPending 이 방해 금지 해제 후 발송
    }
    push(notification);
    return true;
  }

  private boolean deferPush(NotificationSetting setting) {
    ZonedDateTime kstNow = clock.instant().atZone(KST);
    LocalTime time = kstNow.toLocalTime();
    boolean weekend =
        kstNow.getDayOfWeek() == DayOfWeek.SATURDAY || kstNow.getDayOfWeek() == DayOfWeek.SUNDAY;
    return setting.inQuietHours(time) || (setting.weekendOff() && weekend);
  }

  private void push(Notification notification) {
    List<UserDevice> devices = deviceRepository.findAllByUserId(notification.userId());
    boolean sentAny = false;
    for (UserDevice device : devices) {
      if (device.fcmToken() == null) {
        continue;
      }
      FcmSender.Result result =
          fcmSender.send(
              device.fcmToken(),
              notification.title(),
              notification.body(),
              notification.deeplink());
      if (result == FcmSender.Result.UNREGISTERED) {
        device.loggedOut(); // 죽은 토큰 정리 (#44)
        deviceRepository.save(device);
      } else if (result == FcmSender.Result.SENT) {
        sentAny = true;
      }
    }
    if (sentAny) {
      notification.pushSent();
    } else {
      notification.pushSkipped(); // 보낼 기기 없음
    }
    notificationRepository.save(notification);
  }

  /** 방해 금지가 풀린 뒤 보류분 일괄 발송 — 워커가 주기 호출 (#46 「미룬다」 정책). */
  @Transactional
  public int flushPending() {
    int flushed = 0;
    for (Notification pending : notificationRepository.findTop100ByPushStatusOrderById("pending")) {
      NotificationSetting setting = settingOf(pending.userId());
      if (deferPush(setting)) {
        continue;
      }
      push(pending);
      flushed++;
    }
    return flushed;
  }

  @Transactional(readOnly = true)
  public CursorPage<Notification> list(Long userId, String cursor, int limit) {
    long beforeId = cursor == null ? Long.MAX_VALUE : CursorCodec.decode(cursor, SORT).id();
    List<Notification> fetched =
        notificationRepository.findByUserIdAndIdLessThanOrderByIdDesc(
            userId, beforeId, PageRequest.of(0, limit + 1));
    return CursorPage.of(fetched, limit, n -> new Cursor(SORT, List.of(), n.id()));
  }

  @Transactional
  public void readAll(Long userId) {
    notificationRepository.markAllRead(userId);
  }

  @Transactional
  public NotificationSetting settingOf(Long userId) {
    return settingRepository
        .findById(userId)
        .orElseGet(() -> settingRepository.save(new NotificationSetting(userId)));
  }

  @Transactional
  public NotificationSetting updateSetting(
      Long userId,
      Boolean newPosting,
      Boolean dueSoon,
      Boolean boardError,
      Boolean weeklyReport,
      LocalTime quietStart,
      LocalTime quietEnd,
      Boolean weekendOff,
      List<java.util.Map<String, Object>> perBoard) {
    NotificationSetting setting = settingOf(userId);
    setting.update(
        newPosting,
        dueSoon,
        boardError,
        weeklyReport,
        quietStart,
        quietEnd,
        weekendOff,
        perBoard == null ? null : mergePerBoard(userId, setting, perBoard));
    return settingRepository.save(setting);
  }

  /** FCM 토큰 갱신 (#44 계약 추가) — 로그인 사이에 갱신된 토큰을 받는 경로. */
  @Transactional
  public void updateFcmToken(Long userId, String deviceId, String fcmToken) {
    UserDevice device =
        deviceRepository
            .findByUserIdAndDeviceId(userId, deviceId)
            .orElseGet(() -> deviceRepository.save(new UserDevice(userId, deviceId)));
    device.loggedIn(fcmToken, clock.instant());
    deviceRepository.save(device);
  }

  /** 보관 90일 초과분 정리 (#45 보관 정책). */
  @Transactional
  public int cleanupOld() {
    return notificationRepository.deleteOlderThan(clock.instant().minusSeconds(90L * 24 * 3600));
  }
}
