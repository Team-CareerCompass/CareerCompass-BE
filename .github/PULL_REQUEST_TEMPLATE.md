## 무엇을

<!-- 한 줄 요약. 관련 이슈: Closes #N -->

## 왜

<!-- 배경. 스펙(docs/API_SPEC_v0.1.md)과 다르게 구현했다면 반드시 명시하고 스펙 문서도 함께 수정 -->

## 확인 방법

<!-- 리뷰어가 어떻게 검증하나. 테스트 명령 / curl 예시 -->

## 체크리스트

- [ ] `./gradlew build integrationTest` 통과
- [ ] API 응답이 스펙의 `{ok, data}` / `{ok, error}` 계약을 지킨다
- [ ] 스펙과 달라진 점이 있으면 `docs/API_SPEC_v0.1.md` 변경 이력에 남기고 FE 에 알렸다
