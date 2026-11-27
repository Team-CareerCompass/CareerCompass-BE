# CareerCompass — API 스펙 v0.1

> Spring Boot 3.x / Java 21 기준 REST API 초안. 모든 인증 엔드포인트(`/auth/*` 제외)는 `Authorization: Bearer {JWT}` 헤더 필수. 응답은 `application/json; charset=UTF-8`.
>
> **v0.3 (2026-11-16):** FE(develop `2a255ed`)의 `core/network` 가 실제로 읽는 모양에 서버를 맞췄다. 바뀐 자리마다 「v0.3」 표시가 있고, 요약은 §10 변경 이력에 있다.

## 공통 규약

### 경로 접두사 (v0.3)

**모든 경로는 `/api/v1` 아래다.** 이 문서의 경로(`/postings` 등)는 그 아래의 상대 경로로 읽는다 — 예: `GET https://<host>/api/v1/postings`. FE 의 `BASE_URL` 은 `https://<host>/api/v1/` 이다. 헬스체크는 `GET /api/v1/actuator/health`(인증 없음, `Accept` 헤더 없이도 200).

### 직렬화 (v0.3)

- 시각은 오프셋이 붙은 ISO-8601(`2026-05-18T07:00:00Z`), 날짜는 `YYYY-MM-DD`.
- 성공 응답은 언제나 봉투(`{"ok": true, "data": ...}`)가 있다. 204 는 쓰지 않는다.
- 목록은 배열을 그대로 내지 않고 이름 있는 키로 감싼다(`{"boards": [...]}` 등).

### 응답 포맷

성공:
```json
{
  "ok": true,
  "data": { ... }
}
```

실패:
```json
{
  "ok": false,
  "error": {
    "code": "POSTING_NOT_FOUND",
    "message": "공고를 찾을 수 없습니다",
    "field": null
  }
}
```

### 페이징

```
GET /postings?cursor=eyJ...&limit=20
```

응답에 `nextCursor` 포함. cursor 없으면 마지막 페이지.

### HTTP 상태 코드

| 코드 | 의미 |
|---|---|
| 200 | 정상 |
| 201 | 생성 완료 |
| 400 | 검증 실패 |
| 401 | 인증 필요 |
| 403 | 권한 부족 |
| 404 | 리소스 없음 |
| 409 | 충돌 (중복 등) |
| 422 | 비즈니스 룰 위반 |
| 429 | Rate limit |
| 500 | 서버 오류 |
| 503 | 외부 의존성 장애 (LLM 등) |

---

## 1. 인증 (`/auth`)

### `POST /auth/social/{provider}`

소셜 로그인. `provider`는 `kakao` 또는 `google`.

요청:
```json
{
  "accessToken": "...",
  "deviceId": "uuid-v4",
  "fcmToken": "fcm-token-string"
}
```

응답 201:
```json
{
  "ok": true,
  "data": {
    "accessToken": "jwt...",
    "refreshToken": "...",
    "isNewUser": true,
    "expiresIn": 1800,
    "user": {
      "id": 1,
      "email": "user@konkuk.ac.kr",
      "name": null,
      "onboardingDone": false
    }
  }
}
```

**v0.3:** `isNewUser`(처음 가입했거나 온보딩을 마치지 않았으면 true — 앱이 온보딩과 피드를 가른다)와 `expiresIn`(access 토큰 수명, 초)이 필수 필드다. `user` 는 하위 호환으로 남는다. `fcmToken` 은 생략 가능. **구글은 `accessToken` 자리에 ID 토큰(JWT)을 보낸다** — 서버는 tokeninfo 로 검증하고 `aud` 가 `GOOGLE_WEB_CLIENT_ID` 와 같은지 본다(운영 필수 설정). 카카오는 OAuth access token.

### `POST /auth/refresh`

리프레시 토큰으로 토큰 쌍 재발급. 인증 헤더 불필요.

요청:
```json
{ "refreshToken": "..." }
```

응답 200:
```json
{
  "ok": true,
  "data": {
    "accessToken": "jwt...",
    "refreshToken": "...",
    "expiresIn": 1800
  }
}
```

**v0.3:** `expiresIn` 추가. access 토큰에 `jti` 가 있어 같은 초에 재발급해도 이전 토큰과 문자열이 다르다(앱은 같으면 세션을 지운다).

**정책 (v0.2 확정, BE #11):**

- **회전(rotation)**: 재발급마다 refresh 토큰을 새로 발급한다. 응답의 새 refreshToken 으로 반드시 교체 저장할 것.
- **재사용 탐지**: 이미 소비된 refresh 가 다시 오면 **해당 기기 세션의 토큰 계보 전체를 폐기**하고 `AUTH_INVALID`(401) 를 준다. 다른 기기 세션은 끊지 않는다.
- **유예(grace) 30초**: 회전 직후 30초 안에 소비된 토큰이 다시 오면 네트워크 경합으로 보고 정상적으로 새 쌍을 준다. FE 의 single-flight 재발급이 일시 실패를 공유해도 안전하다.
- 만료·무효·폐기된 refresh 는 전부 `AUTH_INVALID`(401) — FE 는 이 코드에서 세션 만료 안내 후 로그인 화면으로 보낸다.
- 액세스 토큰: HS256 JWT, `sub`=userId, 수명 30분. 만료된 액세스 토큰은 `AUTH_REQUIRED`(401), 서명 무효·형식 오류는 `AUTH_INVALID`(401).

### `POST /auth/logout`

**이 기기**의 리프레시 무효화 + FCM 토큰 해제 + 지문 등록 폐기. 인증 필요.

요청:
```json
{ "refreshToken": "..." }
```

**v0.3:** 앱은 `{refreshToken}` 을 보낸다 — 서버는 그 토큰이 가리키는 기기의 세션을 끊는다(모르는·남의 토큰이면 아무 일 없이 200). v0.2 의 `{deviceId}` 도 받는다. 둘 다 없으면 `INVALID_INPUT`.

**정책 (v0.2 확정, BE #12):** 무효화 범위는 요청한 기기뿐이다(전 기기 아님). 액세스 토큰은 블랙리스트 없이 만료(≤30분)까지 유효하다 — 로그아웃 뒤 도착한 옛 액세스 토큰 요청은 만료 전이면 통과하고, 만료 후엔 `AUTH_REQUIRED`. 이미 로그아웃된 상태의 재요청도 멱등하게 200.

### `POST /auth/biometric/register`

지문 빠른 로그인 기기 등록. 인증 필요.

요청:
```json
{ "deviceId": "uuid-v4" }
```

**정책 (v0.2 확정, BE #13):** 등록은 (계정, 기기) 단위로 귀속된다 — 한 기기를 여러 계정이 써도 서로의 등록을 덮지 않는다. 지문 성공 뒤 세션 검증은 별도 엔드포인트 없이 `GET /users/me` 로 갈음한다. 로그아웃·계정 삭제 시 해당 계정×기기의 등록이 폐기된다.

---

## 2. 사용자 프로필 (`/users/me`)

### `GET /users/me`

내 프로필 전체 조회.

응답:
```json
{
  "ok": true,
  "data": {
    "id": 1,
    "name": "정일혁",
    "school": "건국대학교",
    "department": "컴퓨터공학부",
    "gpa": 3.87,
    "gradYear": 2027,
    "jobInterests": [
      { "code": "backend", "priority": 1 },
      { "code": "frontend", "priority": 2 }
    ],
    "tags": ["AI", "스타트업", "환경"],
    "onboardingDone": true,
    "completion": 78
  }
}
```

### `PATCH /users/me`

프로필 부분 수정.

요청:
```json
{
  "name": "정일혁",
  "school": "건국대학교",
  "department": "컴퓨터공학부",
  "gpa": 3.87,
  "gradYear": 2027
}
```

### `PUT /users/me/job-interests`

희망 직무 전체 교체. 최소 1, 최대 3.

```json
{
  "interests": [
    { "code": "backend", "priority": 1 },
    { "code": "frontend", "priority": 2 }
  ]
}
```

### `PUT /users/me/tags`

관심 분야 태그. 최소 1, 최대 5.

```json
{ "tags": ["AI", "스타트업", "환경"] }
```

**정책 (v0.2 확정, BE #15 #16):**

- **onboardingDone 판정**: Step 1(학교·학과 입력)과 Step 2(희망 직무 1개 이상)가 완료되면 true. Step 3 경험·Step 4 지원서는 선택. 한 번 true 가 되면 되돌리지 않는다.
- **completion 산출식(%)**: 이름 10 + 학교 10 + 학과 10 + 학점 10 + 졸업연도 10 + 희망직무 15 + 태그 10 + 경험카드(1개↑) 15 + 과거지원서(1개↑) 10 = 100.
- PATCH 검증: 이름 ≤20자, 학과 ≤30자, 학점 0.0~4.5, 졸업연도 2000~2100. 위반은 `INVALID_INPUT` + `field`.
- 직무·태그 **개수** 위반(직무 0·4개↑, 태그 0·6개↑)은 `LIMIT_EXCEEDED`(422), 형식 위반(알 수 없는 code, 중복 priority, 빈 태그)은 `INVALID_INPUT`(400).
- 태그 정규화: 앞뒤 공백 제거·연속 공백 축약 후 중복은 조용히 제거된다.

### `DELETE /users/me` (v0.3 신설, BE #61)

계정 삭제. 응답 200 `{ "ok": true, "data": null }`.

- **즉시 삭제**, 유예 기간 없음. 재인증(최근 로그인 확인)은 요구하지 않는다 — 앱이 확인 대화상자로 막는다.
- **연쇄 삭제 범위:** 프로필, 희망 직무·관심 태그, 경험 카드, 과거 지원서와 저장소의 원본 파일, 지원서·항목·버전과 지원 이력, 게시판과 수집 공고·파싱 결과·사용자별 공고 상태(읽음·북마크)·적합도, 알림과 알림 설정, 기기(FCM 토큰·지문 등록), refresh 토큰, 모델 개선 동의 기록, 소셜 연결(`users` 행). **남겨 두는 데이터는 없다** — 모델 개선용 익명화 보관도 하지 않는다(#48).
- 삭제 직후 그 계정의 **access 토큰과 refresh 토큰은 모두 401**(`AUTH_INVALID`)이다. 같은 소셜 계정으로 다시 로그인하면 새 계정으로 시작한다.
- 앱 밖 삭제 요청(구글 플레이 요구)은 개인정보처리방침에 적힌 문의 채널로 받고, 운영자가 같은 삭제를 실행한다.

### `GET /meta/schools?q=&limit=` (v0.2 신설, BE #17)

학교 검색(부분 일치). 검색 보조 용도라 커서 없이 `limit`(기본 20, 최대 50)만 지원. `Cache-Control: max-age=86400`.

```json
{ "ok": true, "data": { "schools": [ { "id": 1, "name": "건국대학교" } ] } }
```

### `GET /meta/job-categories` (v0.2 신설, BE #17)

희망 직무 목록 — 한국표준직업분류를 IT·사무 중심으로 간추린 어휘. `code` 는 `PUT /users/me/job-interests` 와 동일. FE 는 이 API 로 로컬 상수를 대체한다.

```json
{ "ok": true, "data": { "categories": [ { "code": "backend", "label": "백엔드 개발" } ] } }
```

---

## 3. 경험 카드 (`/experiences`)

### `GET /experiences?type=project&cursor=...&limit=20`

경험 카드 목록 (최신 등록순).

### `POST /experiences`

경험 카드 생성. `type`은 `project` / `award` / `intern` / `activity` / `cert` 중 하나.

**유형별 `data` 키 (v0.3 — FE `ExperienceMapper` 가 보내는 이름, `*` 는 서버 필수):**

| 유형 | `data` 키 | 기간 |
|---|---|---|
| project | `role`, `techs`(문자열 배열), `summary`, `link` | `startDate*` |
| award | `contestName*`, `rank*`(v0.2 이름 `prize` 도 받음), `year`, `organizer` | 없음 |
| intern | `company*`, `role*`, `summary` | `startDate*` |
| activity | `organization*`, `role`, `summary` — 활동명은 `title` | 선택 |
| cert | `issuer`, `acquiredYearMonth`(`YYYY-MM`) — 자격증명은 `title` | 없음 |

v0.2 는 award 의 `prize`·activity 의 `activityName`·cert 의 `certName` 을 필수로 요구해 앱에서 세 유형 저장이 400 이었다.

요청 (프로젝트 예시):
```json
{
  "type": "project",
  "title": "CareerCompass",
  "startDate": "2025-09-01",
  "endDate": null,
  "data": {
    "role": "프론트엔드",
    "techs": ["Android", "Kotlin", "Compose"],
    "summary": "공고 자동 분석 + AI 자소서 생성 서비스",
    "link": "https://github.com/..."
  }
}
```

### `PATCH /experiences/{id}`

경험 카드 수정. `type` 은 변경 불가.

### `DELETE /experiences/{id}`

경험 카드 삭제.

**정책 (v0.2 확정, BE #18):** 목록 응답은 `{ experiences: [...], nextCursor }`. 최대 30개 초과는 `LIMIT_EXCEEDED`(422), 남의 카드는 404. 유형별 `data` 필수 필드(명세서 F1-3): `award`=contestName·prize, `intern`=company·role(+startDate 필수), `activity`=activityName·organization, `cert`=certName, `project`=startDate 필수. 누락은 `INVALID_INPUT` + `field`(예: `data.prize`).

---

## 4. 과거 지원서 (`/past-applications`)

### `POST /past-applications/upload` (multipart/form-data)

PDF/DOCX/TXT 업로드. 최대 10MB.

요청:
```
file: <binary>
label: "2024 카카오 인턴 자소서"
```

응답 201:
```json
{
  "ok": true,
  "data": {
    "id": 7,
    "label": "2024 카카오 인턴 자소서",
    "filePath": "s3://...",
    "items": [
      { "id": 1, "category": "motivation", "content": "...", "confident": true },
      { "id": 2, "category": "other", "content": "...", "confident": false }
    ]
  }
}
```

### `GET /past-applications`

내 과거 지원서 목록. **v0.3: `{"applications": [...]}` 로 감싼다.** 항목 모양은 업로드 201 응답과 같다.

**분류 어휘 (v0.3):** 바깥 이름은 FE 어휘 `motivation`·`growth`·`experience`·`competency`·`aspiration`·`other` 다. 「성장 배경」은 `growth` 로 내보내고(서버 내부 저장값은 `background`), 카테고리 조정 요청은 `growth`·`background` 둘 다 받는다.

**파일 이름·형식 (v0.3 확정, BE #60):** 업로드 응답과 목록 항목에 `fileName`(올린 원본 파일 이름, 경로는 떼어 낸 마지막 조각)과 `format`(`pdf`·`docx`·`txt`, 실제 파일 확장자 기준)이 실린다. 라벨에서 형식을 읽지 말 것. v0.3 이전에 올린 행은 `fileName` 이 null 일 수 있다.

```json
{ "id": 7, "label": "2024 카카오 인턴 자소서", "fileName": "2024 카카오.pdf", "format": "pdf",
  "filePath": "s3://...", "extractStatus": "done", "items": [ ... ] }
```

### `PATCH /past-applications/{id}` (v0.3 신설, BE #60)

라벨 수정. `{ "label": "고친 라벨" }` → 200, 응답은 목록 항목과 같은 모양. **라벨만 바꾸고 항목·카테고리는 건드리지 않는다.** 빈 라벨·100자 초과는 `INVALID_INPUT`(field=label), 남의 것은 404. 항목 카테고리 조정(`PATCH /past-applications/{appId}/items/{itemId}`)과는 다른 경로다.

### `PATCH /past-applications/{appId}/items/{itemId}`

항목 카테고리 수동 조정.

```json
{ "category": "aspiration" }
```

### `DELETE /past-applications/{id}`

지원서 삭제 (저장소 파일 포함).

**정책 (v0.2 확정, BE #19 #20):**

- 업로드 응답에 `extractStatus`(`done`|`failed`) 필드가 추가된다. 텍스트 추출 실패(스캔 PDF 등) 시 파일은 보관하되 `extractStatus: "failed"`, `items: []`.
- 분류 카테고리 어휘: `motivation`(지원 동기)·`background`(성장 배경)·`experience`(경험 기술)·`competency`(직무 역량)·`aspiration`(입사 후 포부)·`other`(기타). 확신 없는 항목은 `confident: false` — FE 는 「확인 필요」로 표시.
- 분류는 업로드 요청 안에서 동기 처리(201 응답에 items 포함). 현재 분류기는 온프레미스 휴리스틱 — 자소서 원문을 외부로 보내지 않는다. LLM 분류 전환은 CareerCompass-AI 경계(AI#1)·개인정보 결정(#48) 이후.
- 최대 10개 초과는 `LIMIT_EXCEEDED`(422), 형식·크기 위반은 `INVALID_INPUT`(field=file).

---

## 5. 게시판 & 공고 (`/boards`, `/postings`)

### `POST /boards/detect`

URL 입력 시 사이트 구조 자동 감지. 등록 전 미리보기.

요청:
```json
{ "url": "https://konkuk.ac.kr/board/notice" }
```

응답:
```json
{
  "ok": true,
  "data": {
    "detectStatus": "success",
    "selectors": { "title": "...", "link": "...", "date": "..." },
    "preview": [
      { "title": "...", "url": "...", "date": "2026-05-10" },
      ...
    ]
  }
}
```

`detectStatus`는 `success` / `login_required` / `spa` / `blocked` / `failed`.

### `POST /boards`

게시판 등록 확정.

요청:
```json
{
  "url": "https://konkuk.ac.kr/board/notice",
  "name": "건국대 학교 공지사항",
  "type": "scholarship",
  "cycleHours": 24
}
```

### `GET /boards`

내 게시판 목록. **v0.3: `{"boards": [...]}` 로 감싼다.** 항목은 `{id, url, name, type, cycleHours, status, isActive, failCount, lastCollectedAt}`.

**`status` 어휘 (v0.3):** `active`(수집 중) / `paused`(사용자가 끔, `isActive=false`) / `failed`(수집 오류 — 3연속 실패로 꺼진 경우 포함, 재시도로 복구). 오류가 우선이다.

### `PATCH /boards/{id}` / `DELETE /boards/{id}`

게시판 수정/삭제. `isActive` 토글 가능.

### `POST /boards/{id}/retry`

수집 실패 게시판 재시도.

### `GET /postings`

공고 목록.

쿼리 파라미터:
- `boardIds[]` — 게시판 필터
- `types[]` — 공고 유형
- `minScore` — 적합도 최소값 (60/70/80 등)
- `unreadOnly` — 읽지 않은 것만
- `bookmarkedOnly` — 북마크한 것만 (v0.2 추가)
- `q` — 검색어 (v0.3 확정, BE #56): **제목과 `parsed.keywords` 의 부분 문자열**, 대소문자 무시. 수집 원문(`rawContent`)은 뒤지지 않는다. `%`·`_` 는 글자 그대로.
- `dueFrom`, `dueTo` — 마감일 하한·상한, `YYYY-MM-DD`, **그날 포함**. 한쪽만 와도 된다 (v0.3 확정, BE #56. v0.2 이름 `dueAfter`·`dueBefore` 는 별칭으로 남는다)
- `includeNoDueDate` — 마감일 없는 공고(`dueDate: null`)를 넣는가. **기본 true** (v0.3 신설, BE #56)
- `includeExpired` — 마감 지난 공고 포함 여부. 기본 false.

**마감일 규칙 (v0.3 확정, BE #56):** `dueFrom`·`dueTo` 중 하나라도 오면 그 범위만 보고 **「마감 지남 숨김」을 얹지 않는다**(지난 범위 조회가 0건이 되지 않게). 범위가 없을 때만 오늘 이전 마감을 숨기고 `includeExpired=true` 로 끈다. 이때의 「오늘」은 **Asia/Seoul** 기준이다 — 서버가 오늘을 판정하는 자리는 여기 하나뿐이고, 프리셋(7일·30일 이내)은 앱이 절대 날짜로 보낸다. 앱 프리셋 매핑: 전체=`dueFrom=오늘&includeNoDueDate=true`, 7일·30일 이내=`dueFrom=오늘&dueTo=+N&includeNoDueDate=false`, 마감 포함=`includeExpired=true&includeNoDueDate=true`, 직접 지정=있는 쪽만+`includeNoDueDate=false`.

**공고가 아닌 글 (v0.3, BE #65):** AI 가 학사공지 등 공고가 아닌 글로 판정하면(`reasonCode=NOT_A_POSTING`) 서버 내부 상태가 `excluded` 가 되고 **목록·유사 공고·적합도 대상에서 빠진다**. 그래서 카드의 `parseStatus` 는 지금처럼 `pending|done|failed` 셋만 보인다.
- `sort` — `collected_desc`(기본) / `due_asc` / `score_desc`
- `cursor`, `limit`

**정렬 축 계약 (v0.2 확정, BE #28):** `due_asc` 는 마감일이 있는 공고만, `score_desc` 는 적합도가 산출된 공고만 내려간다(정렬 키 없는 행 제외 — 커서 일관성). 카드에는 `parseStatus`(`pending`|`done`|`failed`)가 포함된다(#27) — `score` 가 null 인 이유를 FE 가 「분석 중(pending)/분석 실패(failed)/프로필 미입력(#34)」으로 가른다.

응답:
```json
{
  "ok": true,
  "data": {
    "postings": [
      {
        "id": 101,
        "title": "2026 카카오 SW 인턴십",
        "type": "recruit",
        "board": { "id": 3, "name": "공식 채용" },
        "dueDate": "2026-05-25",
        "collectedAt": "2026-05-18T07:00:00+09:00",
        "score": 88,
        "scoreLabel": "very_suitable",
        "isRead": false,
        "isBookmarked": false
      }
    ],
    "nextCursor": "..."
  }
}
```

### `GET /postings/{id}`

공고 상세 (구조화 결과 + 적합도 분석 + 유사 공고 포함).

**v0.3 (FE 필수 필드):** 상세에도 목록 카드 필드(`type`·`board{id,name}`·`dueDate`·`collectedAt`·`score`·`scoreLabel`·`isRead`·`isBookmarked`)가 실린다. `rawContent` 는 없으면 `""`(null 아님). `parsed.qualifications` 는 늘 `{year, gpa, major}` 키를 가진 객체이고 값은 문자열 또는 null(`gpa` 도 문자열). **`similar` 의 원소는 목록 카드와 같은 모양**이다.

**v0.2 확정 (BE #27 #29):** 응답에 `parseStatus` 가 추가된다. `parsed` 는 `parseStatus=done` 일 때만, `suitability` 는 산출된 경우에만 내려가고 그 외에는 null. `canGenerateApplication`(boolean) 필드가 추가된다 — 지원서 작성 버튼 노출 판단은 서버가 내려주며 장학금·채용만 true. `similar` 는 동일 유형 + 키워드 겹침 + 적합도 순 상위 3건.

**적합도 계약 (v0.2 확정, BE #32 #33 #34):**

- 점수가 없는 카드·상세에는 `scoreUnavailableReason` 이 실린다: `profile_incomplete`(경험 카드 0 + 관심 분야 미입력 — 사용자가 조치 가능) / `parsing_failed`(공고 파싱 실패) / `analyzing`(파싱·산출 대기). **목록과 상세가 같은 이름을 쓴다** — 에러(422)가 아니라 데이터 필드다(목록은 부분 실패이므로).
- `breakdown` 축 이름은 `field_similarity`·`qualification`·`preference`·`competition` 고정. **표시 문구 매핑은 클라이언트가 유지한다** (#33 결정 — FE 기존 방식).
- `strengthComment`/`weaknessComment` 는 서버 생성값. 생성 실패 시 null 로 내려가되 점수는 정상 — FE 는 null 이면 코멘트 영역만 숨긴다.
- **분야 축 (v0.3, AI 계약 §5):** AI 서버가 연결돼 있으면 `field_similarity` 축만 임베딩 유사도(`POST /v1/field-similarity`, 0~100)로 낸다. AI 가 없거나 실패하면 종전처럼 키워드 글자 비교로 낸다. 코멘트의 근거(일치 키워드)는 어느 쪽이든 글자 비교다. 레이블 문턱(80/60/40)은 그대로다.
- 자격 축은 「미기입 항목 충족 간주」(F3-2). 재산출: 프로필·경험 변경 시 해당 사용자 점수를 무효화하고 백그라운드 스윕이 다시 채운다.

응답:
```json
{
  "ok": true,
  "data": {
    "id": 101,
    "title": "...",
    "rawContent": "...",
    "url": "https://...",
    "parsed": {
      "keywords": ["Spring", "Kotlin", "Redis", ...],
      "qualifications": { "year": "2학년 이상", "gpa": null },
      "preferences": ["Java/Kotlin 백엔드 경험", "RDB 1년+"],
      "formQuestions": [
        { "order": 1, "question": "지원 동기를 작성해 주세요", "maxChars": 500 }
      ]
    },
    "suitability": {
      "score": 88,
      "label": "very_suitable",
      "breakdown": [
        { "axis": "field_similarity", "score": 95, "weight": 40 },
        { "axis": "qualification", "score": 88, "weight": 30 },
        { "axis": "preference", "score": 78, "weight": 20 },
        { "axis": "competition", "score": 80, "weight": 10 }
      ],
      "strengthComment": "...",
      "weaknessComment": "..."
    },
    "similar": [ ... ]
  }
}
```

### `POST /postings/{id}/bookmark` / `DELETE`

북마크 토글.

### `POST /postings/{id}/read`

읽음 처리.

---

## 6. 지원서 작성 (`/applications`)

### `POST /applications`

공고 기반 지원서 생성 (AI 초안 트리거).

요청:
```json
{
  "postingId": 101,
  "tone": "formal",
  "items": [
    { "order": 1, "question": "지원 동기를 작성해 주세요", "maxChars": 500 },
    { "order": 2, "question": "직접 추가한 문항", "maxChars": null }
  ]
}
```

**공고당 멱등 (v0.3 확정, BE #57):** 같은 사용자의 같은 `postingId` 에 `status` 가 `generating`·`ready`·`partial_failed` 인 지원서가 있으면 **새로 만들지 않고 그 지원서를 200 으로 돌려준다**(항목과 지금까지 생성된 `answer` 포함). 이때 요청의 `tone`·`items` 는 무시된다. `saved` 인 것만 있으면 새로 만들고 **201**. 동시 요청에도 하나만 만들어진다(DB 부분 유니크 인덱스). 끊긴 스트림을 이어 받을 때 이 재호출을 써도 되고 `GET /applications/{id}` 를 써도 된다 — 둘 다 보장한다. 재호출 시 서버 재시작 등으로 생성이 멈춰 있던 지원서는 생성이 다시 시작된다.

**문항 확정 (v0.3 확정, BE #58):** `items` 는 선택 필드다. 오면 그것이 문항의 정본이고 서버는 `parsed.formQuestions` 를 다시 읽지 않는다. 오지 않으면 파싱 결과로 만든다. `order` 순으로 정렬해 1..n 으로 다시 번호를 매긴다(`order` 생략 시 배열 순서). `maxChars: null` 은 "공고가 제한을 밝히지 않았다"는 뜻이고 그 문항에만 서버 기본 길이(400~600자)가 적용된다. 빈 배열이면 항목 없이 시작한다(직접 추가 경로). 상한: 문항 20개, 질문 500자, `maxChars` 1~5000 또는 null. 위반은 `INVALID_INPUT` 이고 `field` 가 `items` / `items[i].question` / `items[i].maxChars` 다.

응답 (201 새로 만듦 / 200 진행 중인 것을 돌려줌):
```json
{
  "ok": true,
  "data": {
    "id": 42,
    "status": "generating",
    "items": [
      { "id": 1, "order": 1, "question": "지원 동기...", "maxChars": 500, "status": "done", "answer": "..." },
      { "id": 2, "order": 2, "question": "강점과 약점...", "maxChars": 400, "status": "loading", "answer": null }
    ]
  }
}
```

`status`는 `generating` / `ready` / `partial_failed` / `saved`.

**항목 `maxChars` (v0.3):** 응답에서는 **언제나 양수**다. 공고가 제한을 밝히지 않은 문항(요청 `maxChars: null`)은 서버 기본 생성 길이(400~600자)의 상한인 **600** 으로 내려간다. 서버는 「제한 없음」을 따로 기억해 초안 길이를 그 규칙대로 만든다.

**항목 `status` 값 집합 (v0.3 확정, BE #59):** `loading`(생성 중) / `done`(답변 있음 — 생성 완료 또는 사용자 편집) / `failed`(생성 실패, 재생성으로 재시도). 이 셋 말고 다른 값은 보내지 않는다. 실패한 항목은 이후 조회에서도 `failed` 로 보인다.

### `GET /applications/{id}` (v0.2 신설, BE #37)

지원서 단건 조회 — SSE 가 끊겼을 때 현재 상태를 복원하는 경로. 응답은 POST /applications 와 같은 모양 + `postingTitle`/`postingType`/`result`/`scoreSnapshot`.

### `GET /applications/{id}/stream` (SSE)

생성 진행 상황 실시간 스트림. 각 항목 완료 시 이벤트 발송.

```
event: item_done
data: {"itemId": 2, "answer": "..."}
```

**v0.2 확정 (BE #36 #37):**

- 이벤트 3종: `item_done` / `item_failed{itemId}` / `complete{status}`. 15초 간격 heartbeat 주석 전송.
- **페이로드 (v0.3 확정, BE #59):** `item_done` = `{"itemId", "answer"}`, `item_failed` = `{"itemId", "code"}` — `code` 는 §9 값으로 `LLM_UNAVAILABLE`(AI 장애·한도 초과) 또는 `INTERNAL_ERROR`. `complete` = `{"status"}` — 생성 중에 끝나든, 끝난 뒤 구독하든 같은 모양이다. 별도 `event: status` 는 보내지 않는다(최종 상태는 `complete` 가 싣는다).
- **타임아웃 (v0.3 확정, BE #59):** 서버는 스트림을 **4분**에 닫는다(앱 상한 5분보다 짧게). heartbeat 는 15초(앱의 항목당 2분 상한보다 짧게). 닫힌 뒤에는 `POST /applications` 재호출 또는 `GET /applications/{id}` 로 현재 상태를 받는다.
- 구독 시 **이미 끝난 항목의 스냅샷 이벤트를 먼저 보낸다** — 재연결 복원. 생성이 이미 끝난 지원서 구독은 스냅샷+complete 후 즉시 닫힌다. 모바일에서 끊기면 `GET /applications/{id}` 로 동일 상태를 받는다.
- status 전이: `generating` → 전 항목 성공 `ready` / 일부 실패 `partial_failed` → 최종 저장 `saved`. 항목은 독립 생성 — 하나가 실패해도 나머지는 나간다.
- 장학금·채용이 아닌 공고의 생성 요청은 `INVALID_INPUT`(field=postingId) 로 거절.

### `POST /applications/{id}/items` (v0.2 신설, BE #36)

양식 미인식 공고에서 질문 항목을 직접 추가한다. `{question, maxChars}` → 201, 추가 직후 AI 초안이 동기로 생성되어 응답에 담긴다.

### `GET /applications/{id}/items/{itemId}/versions` · `POST .../versions/{versionId}/revert` (v0.2 신설, BE #38)

**v0.3:** `POST .../versions/{versionId}/restore` 도 같은 동작이다(FE canon 의 이름).

재생성·되돌리기 시 이전 답변이 버전으로 보관된다(최대 3개, 초과분은 오래된 것부터 삭제). 되돌리기는 현재 본문도 버전으로 보관한 뒤 교체한다. 재생성은 **성공 시에만** 버전을 소비하며, LLM 장애면 503 과 함께 기존 답변이 유지된다.

### `POST /applications/{id}/items/{itemId}/regenerate`

항목별 재생성. `tone`, `emphasizeCardIds` 옵션.

**응답 시간 상한 (v0.3):** 서버는 생성을 워커에 맡기고 **최대 7초만 기다린다**(앱 read 타임아웃 10초). 그 안에 끝나면 `done`(새 답) 또는 `failed`(이전 답 유지)로, 아니면 `loading` 으로 답하고 생성은 계속된다 — 지원서는 그동안 `generating` 이며, 앱은 `POST /applications` 재호출(같은 id)로 스트림을 열어 `item_done`/`item_failed` 를 받는다. 실패는 503 이 아니라 항목 `status: failed` 로 알린다. 성공 시에만 이전 답이 버전으로 보관된다. 저장본을 재생성하면 저장 상태를 벗어난다.

요청:
```json
{
  "tone": "casual",
  "emphasizeCardIds": [5, 12]
}
```

### `PATCH /applications/{id}/items/{itemId}`

수동 편집.

```json
{ "answer": "..." }
```

### `POST /applications/{id}/save`

최종 저장. 이력 자동 생성.

**v0.3:** 저장본(`saved`)의 항목을 다시 고치거나 되돌리면 지원서는 `ready` 로 돌아간다(같은 공고에 다른 작성 중 지원서가 있으면 `saved` 로 둔다 — 공고당 하나 규칙).

**v0.2 확정 (BE #39):** 글자 수 초과 상태의 저장을 서버가 막지 않는다(경고는 클라이언트 몫). 편집 충돌은 마지막 쓰기 우선(30초 자동 임시 저장 전제). 저장 시 적합도 점수 스냅샷(`scoreSnapshot`)이 이력에 남는다.

### `PATCH /applications/{id}/result`

지원 결과 입력.

```json
{ "result": "pass" }
```

값: `pending` / `pass` / `fail` / `none`.

### `GET /applications`

지원 이력 목록 (status 필터 지원). **v0.3: `{"applications": [...], "nextCursor": ...}` 로 감싸고 `cursor`·`limit`(기본 20, 상한 50) 커서 페이징, 최신순.** 항목은 지원서 응답과 같은 모양(`postingTitle`·`postingType`·`scoreSnapshot`·`result`·`savedAt` 포함).

### `DELETE /applications/{id}`

이력 삭제 (연결된 지원서 함께 삭제).

---

## 7. 신규 기능 (`/feed`, `/roadmap`, `/export`)

### `GET /feed/for-you`

AI 추천 — 톱 픽 + 강점 기반 + 취약점 보완 카테고리별 최대 5건.

**`reason` 타입 (v0.3 결정, BE #55):** 시연 전에는 **현행 유지**한다 — `topPick.reason` 은 문자열 배열, `byStrength[].reason`·`byGap[].reason` 은 문자열. FE 가 이 모양 그대로 받도록 이미 구현돼 있어(FE #190) 지금 바꾸면 앱이 깨진다. 세 자리를 배열로 통일하는 것은 FE 와 같이 바꿀 다음 버전의 일로 남긴다.

응답:
```json
{
  "ok": true,
  "data": {
    "topPick": { "postingId": 101, "reason": [...] },
    "byStrength": [ { "postingId": 102, "reason": "..." }, ... ],
    "byGap": [ { "postingId": 103, "reason": "어학·인턴 보완용" }, ... ]
  }
}
```

### `GET /roadmap/compare?cohort=peer`

`cohort`: `peer` (같은 학과·학년 동기) / `senior` (합격 선배) / `me_only` (내 성장).

응답:
```json
{
  "ok": true,
  "data": {
    "cohort": "peer",
    "sampleSize": 86,
    "metrics": [
      { "name": "프로젝트 수", "me": 4, "peerAvg": 2 },
      { "name": "인턴 경험", "me": 0, "peerAvg": 0.8 }
    ],
    "suggestions": [
      { "semester": "3-2", "action": "SQLD + 토익 800+", "expectedLift": 12 }
    ]
  }
}
```

### `POST /export`

강점 데이터 export.

요청:
```json
{
  "format": "markdown",
  "sections": ["basic", "skills", "projects", "awards", "summary"]
}
```

응답:
```json
{
  "ok": true,
  "data": {
    "format": "markdown",
    "content": "# 정일혁\n*컴퓨터공학부 · 건국대*\n..."
  }
}
```

`format`: `markdown` / `notion` / `html` / `plain`.

---

## 8. 알림 (`/notifications`)

### `GET /notifications`

알림 목록.

### `POST /notifications/read-all`

모두 읽음.

### `POST /notifications/fcm-token` (v0.2 신설, BE #44)

로그인 사이에 갱신된 FCM 토큰을 반영한다. `{deviceId, fcmToken}` → 200.

### `GET /notifications/settings` / `PUT`

설정 조회/수정.

**정책 (v0.2 확정, BE #45~#47):**

- 알림 목록은 최신순 커서 페이징(`cursor`/`limit`/`nextCursor`). 각 알림에 `type`(new_posting|due_soon|board_error|weekly_report)과 `deeplink`(`careercompass://…`)가 실린다. 개별 읽음은 없고 read-all 만(FE 확인 필요 시 추가). 보관 90일 후 자동 삭제.
- 기본값: 신규·마감·수집오류 ON, 주간 리포트 OFF. `weeklyReport` 정의: 지난 7일 신규 공고 수·7일 내 마감 예정 수 요약, 매주 월 09:00 KST.
- `quietHours` 는 KST 기준, 자정 걸침(23:00~08:00) 지원. 방해 금지·주말 OFF 동안 발생한 알림은 **알림함에는 즉시 기록되고 푸시만 보류**되어 해제 후 일괄 발송된다(막지 않고 미룬다).
- 게시판별 OFF 는 신규 공고 알림에 적용. 수집 오류 알림은 게시판 설정과 무관하게 나간다.
- **`perBoard` (v0.3 확정, BE #63):** 조회 응답은 **지금 있는 내 게시판 전부**를 `{boardId, enabled}` 로 낸다(기록이 없으면 `true`, 지운 게시판은 빠진다) — 화면이 이름을 조인하지 못하는 `boardId` 가 생기지 않는다. `PUT` 의 `perBoard` 는 **실린 게시판만 바꾸고 빠진 게시판은 직전 값을 지킨다**(전체 교체 아님). 남의·없는 `boardId` 는 버린다. 그래서 FE 화면 명세의 「사라진 게시판」 줄과 일괄 정리는 필요 없다.
- **주간 리포트 (v0.3 확인, BE #62):** 구현돼 있다 — 매주 월 09:00 KST, 지난 7일 신규 공고 수·7일 안 마감 예정 수 요약, `weeklyReport` 를 켠 계정만, 두 수가 모두 0 이면 보내지 않음, 주당 1건(dedup 키 `weekly:<날짜>`), 알림함에 `type: weekly_report` 로 남고 딥링크는 피드. 방해 금지·주말 끄기는 다른 트리거와 같은 길을 지난다.
- 트리거: 신규 공고(수집 주기당 1건으로 묶음), 마감 D-3·D-1(북마크 공고, 매일 09:00 KST 배치), 수집 오류(3연속 실패). dedup 키로 같은 사건 재발송 금지.

```json
{
  "newPosting": true,
  "dueSoon": true,
  "boardError": true,
  "weeklyReport": false,
  "quietHours": { "start": "23:00", "end": "08:00" },
  "weekendOff": false,
  "perBoard": [
    { "boardId": 3, "enabled": true }
  ]
}
```

---

## 9. 에러 코드 정의

| 코드 | HTTP | 설명 |
|---|---|---|
| `INVALID_INPUT` | 400 | 요청 검증 실패 |
| `AUTH_REQUIRED` | 401 | 토큰 없음/만료 |
| `AUTH_INVALID` | 401 | 토큰 무효 |
| `PERMISSION_DENIED` | 403 | 권한 부족 |
| `RESOURCE_NOT_FOUND` | 404 | 일반 미발견 |
| `POSTING_NOT_FOUND` | 404 | 공고 없음 |
| `DUPLICATE_BOARD` | 409 | 동일 URL 중복 |
| `LIMIT_EXCEEDED` | 422 | 경험 카드 30개 초과 등 |
| `PROFILE_INCOMPLETE` | 422 | 적합도 산출 불가 |
| `PARSING_FAILED` | 422 | 공고 파싱 실패 |
| `BOARD_BLOCKED` | 422 | 접근 차단 게시판 |
| `RATE_LIMITED` | 429 | 호출 횟수 초과 |
| `LLM_UNAVAILABLE` | 503 | LLM API 장애 |
| `INTERNAL_ERROR` | 500 | 기타 서버 오류 |

---

## 10. 변경 이력

- v0.3 (2026-11-16) — **FE 실계약 정렬.** 경로 접두사 `/api/v1`, 로그인 `isNewUser`·`expiresIn`, 재발급 `expiresIn`·`jti`, 로그아웃 `{refreshToken}`, 구글 ID 토큰 검증, 목록 감싸기(`applications`·`boards`), 공고 상세 필드·`similar` 카드 모양, 항목 `maxChars` 양수, 바깥 어휘(`growth`, 게시판 `active|paused|failed`). FE 의 `ApiWireContractSmokeTest` 와 대조할 서버 쪽 스모크는 `ContractSmokeIT`.
- v0.3 (2026-11-16) — **FE 요청 이슈 반영.** #56 검색어·마감일 파라미터, #57 `POST /applications` 공고당 멱등(200/201), #58 요청 `items`, #59 항목 status 값 집합·`item_failed{itemId, code}`·`complete{status}`·SSE 4분, #60 과거 지원서 `fileName`·`format`·라벨 수정, #61 `DELETE /users/me`, #63 `perBoard` 병합, #55 현행 유지 결정, #62 주간 리포트 확인. 저장본 재편집 시 `ready`, 버전 `/restore` 별칭, 재생성 응답 7초 상한.
- v0.3 (2026-11-16) — **AI 서버 연동.** `AI_BASE_URL` 로 CareerCompass-AI 를 부른다(비면 휴리스틱, 기동 로그에 표시, #64). 분야 축 임베딩(AI 계약 §5), 학사공지 제외(#65). BE→AI 호출은 HTTP/1.1 고정(이전엔 h2c 업그레이드로 본문이 사라져 모든 호출이 실패했다).

- v0.2 (2026-09-05) — §1 인증 정책 확정: refresh 회전·재사용 탐지(계보 폐기)·유예 30초, 로그아웃 범위(이 기기)·액세스 토큰 비블랙리스트, 지문 등록의 계정×기기 귀속. FE#79 차단 해소용 — FE 저장소에 공유 필요.
- v0.2 (2026-09-05) — §2 onboardingDone 판정·completion 산출식 확정, `/meta/schools`·`/meta/job-categories` 신설(#17, FE 로컬 상수 대체). §3 목록 응답 형식·유형별 필수 필드. §4 `extractStatus` 필드 추가·분류 어휘 확정(`background` 로 통일). FE 공유 필요.
- v0.2 (2026-09-05) — §5 게시판: detect 처리 상한 ~30초(FE 타임아웃 45초 권장), 등록 즉시 1회 수집, status(active|error)·failCount 로 「수집 오류/일시중지」 구분. §5 공고: `parseStatus` 계약(#27), 검색어 `q`·마감일 범위·`bookmarkedOnly`·`includeExpired` 파라미터 추가, 정렬 축 계약(축 키 없는 행 제외), 상세 `canGenerateApplication` 추가. FE 공유 필요.
- v0.2 (2026-09-05) — §5 적합도: `scoreUnavailableReason`(profile_incomplete|parsing_failed|analyzing) 계약(#34, FE#100 3분기 지원), breakdown 축 이름 고정·표시 문구는 클라이언트 매핑 유지(#33), 코멘트 실패 시 null·점수는 정상. LLM 장애는 `LLM_UNAVAILABLE`(503) — 목록·프로필 등 비 LLM 기능은 영향 없음(#35). FE 공유 필요.
- v0.2 (2026-09-05) — §6 지원서: 단건 조회·항목 직접 추가·버전 목록/되돌리기 신설, SSE 이벤트 3종+스냅샷 복원+heartbeat, status 전이 규칙, 글자 수 초과 저장 허용·마지막 쓰기 우선, scoreSnapshot. 장학금·채용 외 생성 거절. FE 공유 필요.
- v0.2 (2026-09-05) — §7 확정: For You 는 점수 산출 공고만 대상(톱 픽=적합도+마감 D-7 가중, 응답에 title·score 추가), 로드맵은 표본 5명 미만 시 metrics 비움·senior 는 pass+동의 사용자만, Export 의 notion=Notion 호환 마크다운·summary 규칙 기반. §2 `PUT /users/me/consent` 신설(모델 개선 활용 동의, F4-4·#48). FE 공유 필요.
- v0.2 (2026-09-05) — §8 확정: `/notifications/fcm-token` 신설, 알림 목록 커서 페이징·딥링크·타입, quietHours 는 「기록 즉시·푸시 보류」 정책, weeklyReport 정의, 트리거 3종·묶음 발송·dedup. FE 공유 필요.
- v0.1 (2026-05-18) — 초안 작성. 명세서 F1~F4 + 신규 기능 반영.
