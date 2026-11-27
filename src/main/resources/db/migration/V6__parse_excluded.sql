-- V6 — 공고가 아닌 글(학사공지 등)을 「분석 실패」와 가른다 (BE #65, AI 계약 §1.3 reasonCode=NOT_A_POSTING).
-- excluded 는 목록·유사 공고·적합도 대상에서 빠진다.
alter table posting_parsed drop constraint posting_parsed_parse_status_check;
alter table posting_parsed
  add constraint posting_parsed_parse_status_check
    check (parse_status in ('pending', 'done', 'failed', 'excluded'));
