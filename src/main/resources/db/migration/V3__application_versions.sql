-- V3 — 지원서 항목 버전 보관(#38 F4-3: 최대 3버전 되돌리기)과 이력의 적합도 스냅샷(#39 F4-4)

create table application_item_version (
  id                  bigint generated always as identity primary key,
  application_item_id bigint      not null references application_item (id) on delete cascade,
  answer              text,
  tone                varchar(20),
  created_at          timestamptz not null default now()
);
create index ix_item_version_item on application_item_version (application_item_id, id);

alter table application
  add column score_snapshot int; -- 최종 저장 시점의 적합도 (분석 모델 개선 원천, F4-4)
