---
okf_version: "0.2"
---

# SENTINEL_JAVA 문서

SENTINEL_JAVA의 사용법과 변경 이유를 찾는 문서 시작점입니다.

## 구현 상태

Native JDK compiler tree callable inventory, cyclomatic complexity, strict JaCoCo method 결합, exact CRAP 계산과 stable row ordering core, mutate4java 변이 판정 CLI가 구현되어 있습니다. CRAP 상한과 변이 최소 kill 비율은 명령 인자로 받고, 통합 SENTINEL이 부르는 어댑터(`sentinel-tool/`)가 coverage 실행부터 두 판정까지 이어서 돌립니다. history는 다음 구현 범위입니다. [사용법](../README.md)에서 명령과 한계를 확인합니다.

## 설계

* [CRAP core 구조](architecture.md) - source, coverage, exact math와 fail-closed 경계
* [PIT 보고서 연결](pit-report-adapter.md) - 외부 XML 읽기, 정확한 후보 대조와 증거 요구
* [PIT 선택 실행](pit-execution-probe.md) - 고정 버전의 후보 탐색·정상 대조·변이 재실행과 지원 제한

## 운영 기록

* [변경 기록](log.md) - 문서 번들의 생성과 변경 내역
