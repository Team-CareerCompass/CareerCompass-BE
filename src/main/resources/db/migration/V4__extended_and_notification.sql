-- V4 — 모델 개선 활용 동의(F4-4·#48, 로드맵 senior 코호트 #42)와 푸시 발송 상태(M8 방해 금지 보류 #46)

create table user_consent (
  user_id           bigint primary key references users (id),
  model_improvement boolean     not null default false,
  updated_at        timestamptz not null default now()
);

-- 알림함 기록과 푸시 발송을 분리 — 방해 금지 시간대엔 pending 으로 보류 후 일괄 발송
alter table notification
  add column push_status varchar(10) not null default 'pending'
    check (push_status in ('pending', 'sent', 'skipped'));
