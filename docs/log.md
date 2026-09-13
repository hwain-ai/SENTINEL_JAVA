# 변경 기록

## 2026-09-13

- **Update** SelfCrapMain·MutationCommandMain·ProjectMutationRunner: 변경분 검사용 `--only`와 `--changed-file`(반복)을 추가해 판정·변이 대상을 지정한 생산 소스로 좁힌다. 어댑터는 changedFiles와 생산 소스의 교차분을 넘기고, 비면 검사 없이 통과로 응답한다.
- **Creation** GateThreshold.java·sentinel-tool/: CRAP 상한(`--crap-max`, 기본 8)과 변이 최소 kill 비율(`--mutation-min`, 기본 100)을 명령에서 받아 ExactCrap·MutationGate 판정에 쓰고, 통합 SENTINEL의 도구 요청을 받아 Maven+JaCoCo coverage, CRAP 판정, 변이 판정을 이어 돌리는 어댑터와 첫 실행 준비 스크립트를 추가했다. 기준값 문자열 계약은 SENTINEL_SPEC threshold-v1.json과 같고 vendored golden에 복사했다.
- **Update** EvidenceContract.java: crap 구성요소에 crapMax, mutation 구성요소에 mutationMin을 필수로 두고 의미 검사도 그 값으로 재계산한다. SPEC golden 지문 세 사례를 새 값으로 맞췄다. SelfCrapMain은 절대 경로 classpath 항목을 허용해 검사기의 잠긴 jar를 대상 프로젝트 밖에서 지정할 수 있다.

## 2026-09-08

- **Update** pit-execution-probe.md: 12:08 UTC 최종 전체 260 테스트·724 함수 CRAP 통과 기록. 람다 측정 연결 2곳을 이름 있는 연결로 수정했고 기준·제외 범위는 유지.
- **Update** pit-execution-probe.md: 승인되지 않은 실패가 assertAll 내부에 감싸지는 반례를 수정. 중첩 실패도 기존 단언 종류 목록 하나로 검사하고 빈 묶음·과다 중첩을 거부.
- **Update** pit-execution-probe.md: PIT 공식 core API로 후보별 정상 대조 2회·변이 재실행 2회를 연결. 입력·결과·실패 위치 지문과 비인증 경계 기록.
- **Update** pit-report-adapter.md, index.md: 새 증거 수집 상태를 연결. 경로 교체의 외부 쓰기·삭제, 건너뛴 동적 테스트, 상속·묶인 실패의 반례와 수정 기록.
- **Update** pit-execution-probe.md: 실제 강/약 PIT 실행과 전체 232 테스트·670 함수 CRAP 통과 기록. 인증 결과와 분리.
- **Creation** pit-execution-probe.md: 고정 PIT 1.30.0/JUnit 실행, 별도 복사본 후보 탐색·결과 대조, 비인증 CLI와 지원 제한 기록.
- **Update** pit-report-adapter.md, index.md: 실제 선택 실행 문서 연결. architecture.md의 type 누락과 기존 log 태그만 표준화.
- **Creation** pit-report-adapter.md: PIT 1.30.0 일반 XML 읽기·후보 대조·증거 요구, 제한과 후속 실행 연결 조건 기록.
- **Update** index.md: PIT 보고서 연결 색인 추가. 기본 backend·잠금·실행 경로는 유지.

## 2026-09-03

- **Update** Java 17 분석: enum·비정적 member constructor의 실제 JVM descriptor, generic·intersection lambda의 위치 독립 ID, 잘못된 외부 classpath 사용 방지를 추가.
- **Update** JaCoCo와 toolchain: XML element·special descriptor 검증, 순환 symlink 안전 오류, owner-only runtime directory와 lock metadata 검증을 추가.
- **Creation** Java native CRAP core: callable inventory, independent CC ownership, strict JaCoCo join, exact fraction/decimal과 deterministic sorting을 추가.
- **Creation** Repository-local toolchain bootstrap와 launchers: 승인되지 않은 checksum과 incomplete installed tree는 ambient runtime fallback 없이 거부.
- **Update** README.md, index.md, architecture.md: 구현된 library 범위, 검증법과 아직 남은 CLI/mutation 범위를 기록.
- **Creation** SENTINEL_JAVA 문서 번들: OKF v0.2 init 골격 생성.
