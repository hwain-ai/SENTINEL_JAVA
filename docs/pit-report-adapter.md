---
type: Implementation Note
status: draft
generated: { by: process:codex, at: 2026-09-08T12:08:06Z }
sources:
  - resource: ../src/main/java/io/github/hwainhwang/sentinel/mutation/PitReportAdapter.java
    title: PIT XML 읽기
  - resource: ../src/main/java/io/github/hwainhwang/sentinel/mutation/PitReport.java
    title: 후보 대조와 기존 증거 검증 연결
  - resource: https://github.com/hcoles/pitest/blob/1.30.0/pitest-entry/src/main/java/org/pitest/mutationtest/report/xml/XMLReportListener.java
    title: PIT 1.30.0 공식 XML 작성 코드
stale_after: 2026-10-08
---

# PIT 보고서 연결

Java에는 외부 PIT 1.30.0의 XML 보고서를 읽고 SENTINEL의 후보·증거 검사로 연결하는 adapter를 추가했다. 제한된 소스 프로필의 [별도 선택 실행 CLI](pit-execution-probe.md)도 있으며, 기본 backend 교체나 엄격한 증거 수집 전체를 완료한 것은 아니다.

PIT는 Java 코드에 일부러 변화를 주어 테스트가 오류를 찾는지 검사하는 도구다. XML은 그 결과를 저장하는 문서 형식이다. KILLED 표시는 PIT에서 변이가 검출됐다는 뜻이며, SENTINEL에서 요구하는 정상 코드 대조와 같은 실패 재현을 자체적으로 증명하지는 않는다.

## 처리와 경계

1. PitReportAdapter.parse가 일반 XML을 읽는다. 최대 8 MiB, 요소 깊이 4이며 DTD·외부 참조·XInclude를 허용하지 않는다. 알 수 없는 요소·속성·상태, 중복 식별자, 잘못된 숫자와 detected/status 불일치를 거부한다. 오류에는 원시 XML·로컬 경로를 넣지 않는다.
2. 변이 식별자는 클래스·메서드·JVM 서명·변이 연산자·명령어 인덱스로 구성한다. 화면에 보이는 줄 번호만으로 다른 변이를 합치지 않는다.
3. joinInventory는 실행 전에 독립적으로 확보한 예상 후보 전체 목록과 정확히 대조한다. 보고서에 빠지거나 추가된 후보는 오류다. 읽은 보고서로 예상 목록을 다시 만들어 통과시키면 안 된다.
4. normalize는 같은 소스 복사본에 연결된 기존 MutationCandidate와 MutationProof를 받는다. KILLED에 proof가 없으면 pitProofMissing을 내고, proof가 있으면 기존 MutationNormalizer로 단정문 실패·대조 실행·재실행을 검사한다. XML의 killingTest 문자열을 증거로 사용하지 않는다.

입력은 fullMutationMatrix=false인 일반 보고서만 지원한다. 큰 보고서나 matrix 형식을 일부 잘라 처리하지 않는다. mutations의 partial 속성은 partialCoverage이며 실행 결과가 전부 수집됐는지를 뜻하지 않는다. 독립적인 후보 목록 대조가 별도로 필요한 이유다. [공식 작성 코드](https://github.com/hcoles/pitest/blob/1.30.0/pitest-entry/src/main/java/org/pitest/mutationtest/report/xml/XMLReportListener.java)

| PIT 상태 | SENTINEL 처리 |
|---|---|
| KILLED | 증거 검증 후 상태 결정. 표시만으로 killed 인정하지 않음 |
| SURVIVED / NO_COVERAGE | survived / uncovered |
| TIMED_OUT | timedOut. detected=true여도 killed 아님 |
| NON_VIABLE | toolError. Java 소스 컴파일 실패라고 추정하지 않음 |
| MEMORY_ERROR / RUN_ERROR | runtimeError |
| NOT_STARTED / STARTED | pending |
| EQUIVALENT | ignored. 엄격한 검사 통과에서 제외할 수 없음 |

상태의 detected 값은 [PIT 1.30.0 정의](https://github.com/hcoles/pitest/blob/1.30.0/pitest/src/main/java/org/pitest/mutationtest/DetectionStatus.java)를 따른다. 이 문서는 외부 보고서 계약을 다루므로 한 달 뒤 다시 확인한다.

## 검증과 다음 단계

신규 adapter 테스트는 47개이며 실제 PIT 실험의 약한 테스트 보고서, 2개 SURVIVED·1개 KILLED를 포함한다. 누락·중복 후보, 증거 없는 검출, XML 외부 참조·과다 입력·알 수 없는 필드를 검사한다. [테스트](../src/test/java/io/github/hwainhwang/sentinel/mutation/PitReportAdapterTest.java), [실험 보고서](../src/test/resources/pit-1.30.0/observed-weak.xml)

2026-09-08 scripts/self-crap.sh 실행에서 전체 210개 테스트가 실패·오류·건너뛰기 없이 통과했다. CRAP 검사는 복잡도와 테스트 실행 범위를 함께 평가하며, 함수 596개 모두 측정 가능하고 기준 초과 0개였다. 새 람다를 분석기가 연결하지 못한 문제는 이름 있는 함수로 바꾸어 해결했고 기준을 낮추지 않았다.

별도 PitProbeMain은 기본 backend를 바꾸지 않고 PIT를 실행한다. 실행 계약과 제한은 [PIT 선택 실행](pit-execution-probe.md)에 기록한다. 남은 연결은 다음과 같다.

1. 선택 실행은 PIT·JUnit 배포 파일의 버전·크기·SHA-256·라이선스를 고정하고, 공식 dry-run의 별도 후보 목록과 실행 결과를 대조한다. 후보 완전성의 대상은 명시한 프로필과 고정 연산자 3종이지 Java 전체가 아니다.
2. 선택 실행은 소스·클래스·계획·후보 지문에 연결된 정상 대조 2회·변이 재실행 2회를 수집한다. 제한된 일반 JUnit 메서드 프로필의 증거는 replays에 별도 출력하며 인증 판정은 유지한다. 이 보고서 API를 별도로 호출할 때 외부 XML과 proof 객체를 연결하는 책임은 여전히 호출자에게 있다.
3. 실제 프로젝트에서 기존 mutate4java와 나란히 비교하고 누락·비정상 중단·시간·복구를 확인한다.
4. 통과한 범위만 별도 승인 후 기본값으로 전환한다. 그 전에는 기존 backend 잠금과 실행 경로를 유지하므로 새 연결의 문제로 기존 결과 의미가 바뀌지 않는다.
