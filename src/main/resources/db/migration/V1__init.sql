-- V1__init.sql — Figma ERD 16 엔티티 + 인증 보조 2개(user_device, refresh_token; M1 이슈 #10~#13 요구)
-- 규칙: PK 는 BIGINT IDENTITY, 시각은 TIMESTAMPTZ(UTC). (최종보고서 4.3)

-- ─── User Domain ────────────────────────────────────────────────

create table users (
  id           bigint generated always as identity primary key,
  provider     varchar(20)  not null,               -- kakao | google
  provider_id  varchar(100) not null,
  email        varchar(255),
  name         varchar(50),
  created_at   timestamptz  not null default now(),
  updated_at   timestamptz  not null default now(),
  deleted_at   timestamptz,                          -- soft delete
  constraint uk_users_provider unique (provider, provider_id)
);

create table user_profile (
  user_id         bigint primary key references users (id),
  school          varchar(100),
  department      varchar(100),
  gpa             numeric(3, 2),
  grad_year       int,
  onboarding_done boolean     not null default false,
  updated_at      timestamptz not null default now()
);

create table user_job_interest (
  id       bigint generated always as identity primary key,
  user_id  bigint      not null references users (id),
  code     varchar(50) not null,
  priority int         not null,                     -- 1~3, 앱에서 개수 상한 강제 (§2)
  constraint uk_job_interest unique (user_id, code),
  constraint uk_job_interest_priority unique (user_id, priority)
);

create table user_tag (
  id      bigint generated always as identity primary key,
  user_id bigint      not null references users (id),
  tag     varchar(50) not null,                      -- 1~5개, 앱에서 상한 강제 (§2)
  constraint uk_user_tag unique (user_id, tag)
);

create table experience_card (
  id         bigint generated always as identity primary key,
  user_id    bigint       not null references users (id),
  type       varchar(20)  not null
    check (type in ('project', 'award', 'intern', 'activity', 'cert')),  -- 유형 5종 (§3)
  title      varchar(100) not null,
  start_date date,
  end_date   date,
  data       jsonb        not null default '{}'::jsonb,  -- 유형별 필드 (role/techs/summary/link …)
  created_at timestamptz  not null default now(),
  updated_at timestamptz  not null default now(),
  deleted_at timestamptz                              -- soft delete, 최대 30개는 앱에서 강제 (F1-3)
);
create index ix_experience_card_user on experience_card (user_id, created_at desc);

create table past_application (
  id              bigint generated always as identity primary key,
  user_id         bigint       not null references users (id),
  label           varchar(100) not null,
  file_path       varchar(500) not null,             -- S3 key (원본은 S3, DB 는 참조만)
  file_type       varchar(10)  not null,             -- pdf | docx | txt
  file_size_bytes bigint       not null,
  extract_status  varchar(20)  not null default 'pending'
    check (extract_status in ('pending', 'done', 'failed')),
  created_at      timestamptz  not null default now()
);
create index ix_past_application_user on past_application (user_id);

create table past_application_item (
  id                  bigint generated always as identity primary key,
  past_application_id bigint      not null references past_application (id) on delete cascade,
  category            varchar(20) not null
    check (category in ('motivation', 'background', 'experience', 'competency', 'aspiration', 'other')),
  content             text        not null,
  confident           boolean     not null default true,
  item_order          int         not null
);

-- ─── Crawler Domain ─────────────────────────────────────────────

create table board (
  id                bigint generated always as identity primary key,
  user_id           bigint        not null references users (id),
  url               varchar(1000) not null,
  name              varchar(100)  not null,
  type              varchar(20)   not null,          -- scholarship | contest | recruit | activity | mixed
  selectors         jsonb,                           -- 감지된 title/link/date 셀렉터
  cycle_hours       int           not null default 24,
  status            varchar(20)   not null default 'active'
    check (status in ('active', 'error', 'disabled')),
  is_active         boolean       not null default true,
  fail_count        int           not null default 0,
  last_collected_at timestamptz,
  created_at        timestamptz   not null default now(),
  updated_at        timestamptz   not null default now(),
  constraint uk_board_user_url unique (user_id, url)  -- URL 중복 409 (F2-1, issue #2)
);

create table posting (
  id           bigint generated always as identity primary key,
  board_id     bigint        not null references board (id) on delete cascade,
  title        varchar(500)  not null,
  url          varchar(1000) not null,
  url_hash     varchar(64)   not null,               -- sha-256(url) — 중복 수집 방지 (F2-2)
  raw_content  text,
  type         varchar(20)   not null default 'other'
    check (type in ('scholarship', 'contest', 'recruit', 'activity', 'other')),
  due_date     date,
  posted_date  date,
  collected_at timestamptz   not null default now(),
  constraint uk_posting_board_url unique (board_id, url_hash)
);
create index ix_posting_board_collected on posting (board_id, collected_at desc);
create index ix_posting_due on posting (due_date);

create table posting_parsed (
  posting_id     bigint primary key references posting (id) on delete cascade,
  parse_status   varchar(20) not null default 'pending'
    check (parse_status in ('pending', 'done', 'failed')),   -- 공고 응답 계약에 노출 (issue #27)
  keywords       jsonb,
  qualifications jsonb,
  preferences    jsonb,
  form_questions jsonb,
  parsed_at      timestamptz
);

create table posting_user_state (
  id            bigint generated always as identity primary key,
  user_id       bigint  not null references users (id),
  posting_id    bigint  not null references posting (id) on delete cascade,
  is_read       boolean not null default false,
  is_bookmarked boolean not null default false,
  read_at       timestamptz,
  bookmarked_at timestamptz,
  constraint uk_posting_user_state unique (user_id, posting_id)
);

create table suitability_score (
  id               bigint generated always as identity primary key,
  user_id          bigint      not null references users (id),
  posting_id       bigint      not null references posting (id) on delete cascade,
  score            int         not null check (score between 0 and 100),
  label            varchar(20) not null,             -- very_suitable | suitable | normal | low
  breakdown        jsonb       not null,             -- 4축 [{axis, score, weight}]
  strength_comment text,
  weakness_comment text,
  computed_at      timestamptz not null default now(),
  constraint uk_suitability unique (user_id, posting_id)  -- 중복 산출 방지 + 24h 캐시 (issue #2)
);

-- ─── Application Domain ─────────────────────────────────────────

create table application (
  id         bigint generated always as identity primary key,
  user_id    bigint      not null references users (id),
  posting_id bigint      not null references posting (id),
  status     varchar(20) not null default 'generating'
    check (status in ('generating', 'ready', 'partial_failed', 'saved')),
  tone       varchar(20) not null default 'formal',
  result     varchar(10) not null default 'none'
    check (result in ('pending', 'pass', 'fail', 'none')),
  saved_at   timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz                              -- 이력 삭제 시 지원서 함께 삭제 (F4-4)
);
create index ix_application_user on application (user_id, created_at desc);

create table application_item (
  id             bigint generated always as identity primary key,
  application_id bigint      not null references application (id) on delete cascade,
  item_order     int         not null,
  question       text        not null,
  max_chars      int,
  answer         text,
  status         varchar(20) not null default 'loading'
    check (status in ('loading', 'done', 'failed')),
  updated_at     timestamptz not null default now(),
  constraint uk_application_item_order unique (application_id, item_order)
);

-- ─── Notification ───────────────────────────────────────────────

create table notification (
  id         bigint generated always as identity primary key,
  user_id    bigint       not null references users (id),
  type       varchar(20)  not null
    check (type in ('new_posting', 'due_soon', 'board_error', 'weekly_report')),
  title      varchar(200) not null,
  body       varchar(500),
  deeplink   varchar(500),
  dedup_key  varchar(150) not null,                  -- 동일 트리거 중복 발송 금지 idempotency 키
  is_read    boolean      not null default false,
  created_at timestamptz  not null default now(),
  constraint uk_notification_dedup unique (user_id, dedup_key)
);
create index ix_notification_user on notification (user_id, created_at desc);

create table notification_setting (
  user_id       bigint primary key references users (id),
  new_posting   boolean     not null default true,
  due_soon      boolean     not null default true,
  board_error   boolean     not null default true,
  weekly_report boolean     not null default false,
  quiet_start   time,
  quiet_end     time,
  weekend_off   boolean     not null default false,
  per_board     jsonb       not null default '[]'::jsonb,  -- [{boardId, enabled}]
  updated_at    timestamptz not null default now()
);

-- ─── 인증 보조 (ERD 외 — M1 #10~#13) ───────────────────────────

create table user_device (
  id                bigint generated always as identity primary key,
  user_id           bigint       not null references users (id),
  device_id         varchar(100) not null,
  fcm_token         varchar(255),
  biometric_enabled boolean      not null default false,
  last_login_at     timestamptz,
  created_at        timestamptz  not null default now(),
  constraint uk_user_device unique (user_id, device_id)
);

create table refresh_token (
  id         bigint generated always as identity primary key,
  user_id    bigint       not null references users (id),
  device_id  varchar(100) not null,
  token_hash varchar(64)  not null,                  -- 원문 저장 금지, sha-256 해시만
  family_id  uuid         not null,                  -- 회전 계보 — 재사용 탐지 시 계보 전체 폐기 (#11)
  issued_at  timestamptz  not null default now(),
  expires_at timestamptz  not null,
  rotated_at timestamptz,
  revoked_at timestamptz,
  constraint uk_refresh_token_hash unique (token_hash)
);
create index ix_refresh_token_user on refresh_token (user_id);
create index ix_refresh_token_family on refresh_token (family_id);
