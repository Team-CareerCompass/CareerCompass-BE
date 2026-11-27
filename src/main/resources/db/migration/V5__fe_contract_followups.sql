-- V5 — FE 계약 보강 (2026-11, BE #57 #60)

-- #57 공고당 진행 중 지원서는 하나 — 동시 요청에도 하나만 생기게 DB 가 마지막으로 막는다.
-- saved 는 제외: 저장을 마친 뒤 같은 공고에 다시 쓰는 것은 정상 경로다.
create unique index uk_application_in_progress
  on application (user_id, posting_id)
  where status <> 'saved' and deleted_at is null;

-- #60 과거 지원서 목록이 원본 파일 이름을 말할 수 있게 한다. 형식은 기존 file_type(pdf|docx|txt)을 그대로 쓴다.
alter table past_application
  add column file_name varchar(255);
