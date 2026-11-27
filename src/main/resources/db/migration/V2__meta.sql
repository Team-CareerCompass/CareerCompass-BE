-- V2__meta.sql — 학교·직무 목록 (#17). FE 로컬 상수를 걷어내기 위한 조회용 메타 테이블.

create table school (
  id   bigint generated always as identity primary key,
  name varchar(100) not null unique
);

create table job_category (
  id         bigint generated always as identity primary key,
  code       varchar(50)  not null unique,
  label      varchar(100) not null,
  sort_order int          not null
);

insert into school (name) values
  ('건국대학교'), ('가천대학교'), ('가톨릭대학교'), ('강원대학교'), ('경북대학교'),
  ('경희대학교'), ('고려대학교'), ('광운대학교'), ('국민대학교'), ('단국대학교'),
  ('동국대학교'), ('동덕여자대학교'), ('명지대학교'), ('부산대학교'), ('상명대학교'),
  ('서강대학교'), ('서울과학기술대학교'), ('서울대학교'), ('서울시립대학교'), ('성균관대학교'),
  ('성신여자대학교'), ('세종대학교'), ('숙명여자대학교'), ('숭실대학교'), ('아주대학교'),
  ('연세대학교'), ('영남대학교'), ('울산대학교'), ('이화여자대학교'), ('인하대학교'),
  ('전남대학교'), ('전북대학교'), ('제주대학교'), ('중앙대학교'), ('충남대학교'),
  ('충북대학교'), ('한국외국어대학교'), ('한국항공대학교'), ('한동대학교'), ('한양대학교'),
  ('홍익대학교'), ('KAIST'), ('POSTECH'), ('GIST'), ('DGIST'), ('UNIST');

-- 한국표준직업분류를 IT·사무 중심으로 간추린 어휘 — PUT /users/me/job-interests 의 code 와 동일 (#16)
insert into job_category (code, label, sort_order) values
  ('backend',   '백엔드 개발',        1),
  ('frontend',  '프론트엔드 개발',    2),
  ('mobile',    '모바일 앱 개발',     3),
  ('ai_ml',     'AI·머신러닝',        4),
  ('data',      '데이터 엔지니어링·분석', 5),
  ('devops',    'DevOps·인프라',      6),
  ('security',  '정보보안',           7),
  ('game',      '게임 개발',          8),
  ('embedded',  '임베디드·IoT',       9),
  ('qa',        'QA·테스트',          10),
  ('pm',        '서비스 기획·PM',     11),
  ('design',    'UX·UI 디자인',       12),
  ('research',  '연구·R&D',           13),
  ('marketing', '마케팅',             14),
  ('sales',     '영업·세일즈',        15),
  ('finance',   '금융·회계',          16),
  ('hr',        '인사·노무',          17),
  ('education', '교육',               18),
  ('media',     '미디어·콘텐츠',      19),
  ('public',    '공공·행정',          20);
