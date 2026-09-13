---
type: Implementation Note
status: draft
generated: { by: process:codex, at: 2026-09-08T12:08:06Z }
sources:
  - resource: ../src/main/java/io/github/hwainhwang/sentinel/mutation/PitProbe.java
    title: 선택 실행과 결과 계약
  - resource: ../src/main/resources/pit-probe-artifacts.tsv
    title: 배포 파일 버전·크기·SHA-256·라이선스 고정 목록
  - resource: ../src/main/java/io/github/hwainhwang/sentinel/mutation/PitReplays.java
    title: 후보별 정상 대조와 변이 재실행의 증거 대조
  - resource: ../src/main/java/io/github/hwainhwang/sentinel/mutation/junit/SentinelTestExecutionListener.java
    title: JUnit 테스트 목록·결과·실패 위치 관측
  - resource: https://github.com/hcoles/pitest/blob/1.30.0/pitest/src/main/java/org/pitest/mutationtest/engine/gregor/GregorMutater.java
    title: PIT 공식 후보 탐색과 변이 클래스 생성
  - resource: https://github.com/hcoles/pitest/blob/1.30.0/pitest-entry/src/main/java/org/pitest/mutationtest/build/DryRunUnit.java
    title: PIT 1.30.0 독립 dry-run 결과 작성
  - resource: https://github.com/hcoles/pitest/blob/1.30.0/pitest-command-line/src/main/java/org/pitest/mutationtest/commandline/OptionsParser.java
    title: PIT 공식 명령줄 옵션
stale_after: 2026-10-08
---

# PIT 선택 실행

Java에 실제 PIT 1.30.0 실행과 후보별 정상 코드 2회·변이 코드 2회 재검사를 연결했다. 이 결과는 인증된 품질 통과가 아니며, 기존 mutate4java 기본 실행과 승인 상태를 바꾸지 않는다. 사용자가 승인한 다음 단계 구현과 도구의 운영 인증은 구분한다.

## 선택한 연결 방식

| 접근 | 장점 | 현재 단계의 부담 |
|---|---|---|
| 기존 Maven 프로젝트에 PIT 플러그인 실행 | 프로젝트 빌드 설정·의존성을 재사용하기 쉬움 | 임의 POM 실행, 전이 의존성 고정, 빌드 확장·리소스와 격리 범위를 함께 검증해야 함 |
| 별도 javac·JUnit·PIT 실행 | 고정된 도구 파일과 명령을 직접 확인 가능 | 지원하는 프로젝트 범위가 좁음 |

이번에는 두 번째를 선택했다. 후보 탐색·원래 관측에는 공식 CLI를, 정확한 후보의 재생성에는 고정 core JAR의 공식 바이트코드 API를 호출한다. 바이트코드는 컴파일된 Java 명령어이며, PIT 변이 규칙 본문은 복사하지 않는다. 범용 Maven/Gradle 프로젝트 지원이 필요해지면 빌드 연결을 별도로 추가할 수 있고, 현재 실험 결과를 소급해 인증하지 않는다. [실행 명령](../src/main/java/io/github/hwainhwang/sentinel/mutation/PitProbeCommands.java), [외부 라이브러리 연결](../src/main/java/io/github/hwainhwang/sentinel/mutation/PitReplayEngine.java)

## 실제 처리 순서

1. 배포 JAR 7개의 크기와 SHA-256을 확인한다. 소스는 파일당 4 MiB, 합계 16 MiB, 256개 이하로 읽고 심볼릭 링크·하드 링크를 거부한다.
2. 소스만 복사한 비공개 임시 폴더에서 Java 17 대상으로 컴파일한다. 주석 처리기 실행은 끄고, 명시한 대상·테스트 클래스가 실제로 생성됐는지 확인한다. 생성 클래스도 크기·개수 한도를 적용해 보관하고 실행 뒤 바뀌었는지 비교한다.
3. JUnit 정상 코드 테스트를 통과시킨 뒤 PIT의 공식 dry-run을 실행한다. 이 단계도 커버리지를 얻기 위해 테스트를 실행하지만 변이는 실행하지 않는다. 후보 상태는 NOT_STARTED 또는 NO_COVERAGE만 허용한다. [공식 dry-run 구현](https://github.com/hcoles/pitest/blob/1.30.0/pitest-entry/src/main/java/org/pitest/mutationtest/build/DryRunUnit.java)
4. 처음 캡처한 소스로 새 임시 폴더를 만들고 다시 컴파일해 실제 PIT 변이를 실행한다. dry-run에서 실행된 테스트가 다음 실행의 소스·클래스를 남겨놓지 않는다.
5. 독립 dry-run 후보와 실제 XML의 클래스·메서드·JVM 서명·연산자·명령어 인덱스를 정확히 대조한다. 빠진 후보와 추가 후보는 오류다. XML의 partial 속성이나 프로세스 종료 0으로 완전성을 추정하지 않는다.
6. 각 후보마다 정상 코드, 변이 코드, 정상 코드, 변이 코드를 차례로 새 임시 폴더에 컴파일해 실행한다. PIT core가 실제 반환한 후보의 전체 식별자·파일·줄을 다시 대조하고 해당 클래스만 바꾼다. 다른 실행의 빌드 결과나 테스트 캐시를 재사용하지 않는다.
7. JUnit의 형식화된 실행 기록에서 전체 테스트 목록, 테스트별 결과, 실패한 테스트·예외 종류·메시지와 스택 위치의 지문을 비교한다. 정상 코드는 두 번 모두 통과해야 하고, 검출 인정에는 두 변이 실행의 동일한 단언 실패가 필요하다. 단언은 테스트가 기대값과 실제값을 비교하는 검사다. 값 자체와 원시 스택은 출력하지 않는다.
8. 소스·원본 클래스·변이 클래스·실행기·PIT 파일 지문과 후보 계획을 연결한다. 실행별 임의 식별자 nonce 4개가 달라야 한다. 원본 소스와 고정 도구가 그대로인지 확인한 뒤 정렬된 JSON을 출력한다. planSha256은 소스 집합, 명시 클래스 범위, 연산자, 전체 후보 식별자, 도구와 실행기 지문을 묶지만 POM이나 프로젝트 전체 파일의 지문은 아니다.

## 지원 범위와 실행

Linux, Java 17, JUnit 5, src/main/java와 src/test/java 표준 배치의 소스 프로젝트만 지원한다. 대상 클래스와 테스트 클래스는 각각 최대 64개까지 정확한 이름으로 지정한다. 와일드카드·중복 이름·빈 범위를 거부한다. 추가 프로젝트 의존성, 리소스 디렉터리, module-info.java, 주석 처리기, Kotlin·다른 JVM 언어는 현재 범위 밖이다. Maven/Gradle 설정은 읽거나 실행하지 않는다. POM이 존재해도 그 프로젝트의 원래 빌드를 재현했다는 의미가 아니다.

재검사는 Jupiter의 일반 @Test 메서드와 상속받은 일반 테스트, 그리고 실행 중에 등록되는 @TestFactory·@ParameterizedTest·@RepeatedTest 의 테스트를 지원한다(2026-09-13 변경). 등록된 테스트 식별자는 재고 지문에 들어가므로 두 대조 실행이 같은 테스트를 만들어야 한다. 건너뛴 테스트(@Disabled, 비활성 클래스)도 재고에 기록해 두 실행이 같은 집합을 건너뛰어야 하고, 실행된 테스트가 하나도 없거나 가정 실패로 중단된 컨테이너가 있으면 성공으로 세지 않는다. 자동 확장 검색과 병렬 테스트 실행은 끈다. 명시적인 프로젝트 테스트 코드 자체를 격리하는 것은 아니다. 프로젝트가 SENTINEL·JUnit·OpenTest4J의 클래스 이름을 덮어쓰는 경우도 거부한다.

PIT가 finally 블록 등의 여러 명령어 위치를 하나로 묶은 후보는 pitReplayGroupedUnsupported로 거부한다. 공식 core에서 재생성할 때 반환하는 상세 식별자가 전체 묶음과 동일함을 현재 연결에서 검증할 수 없기 때문이다. 지원하지 않는 후보를 생략하고 전체 검사를 통과시키지 않는다.

실험용 변이 연산자는 CONDITIONALS_BOUNDARY, NEGATE_CONDITIONALS, TRUE_RETURNS 3종으로 고정했다. 조건 경계 변경, 조건 반전, 참 반환 치환이며 PIT의 DEFAULTS 변경에 따라 선택 범위가 조용히 달라지지 않는다. 독립 후보 대조가 이 제한된 범위 밖의 누락까지 증명하지는 않는다.

저장소의 도구 설치가 완료된 뒤 scripts/bootstrap-pit-probe.sh를 직접 실행한다. 이어 scripts/mvn.sh test로 Java 클래스를 준비한다. 실행은 scripts/java.sh에 -cp target/classes와 io.github.hwainhwang.sentinel.cli.PitProbeMain을 전달한다. 이 실행 클래스는 기존 MutationCommandMain과 분리되어 있다.

| CLI 인자 | 의미 |
|---|---|
| --project PATH | 실행할 소스 프로젝트 경로 |
| --artifacts PATH | scripts/bootstrap-pit-probe.sh가 설치한 .toolchain/pit-probe 경로 |
| --target-class CLASS | 변경할 정확한 클래스 이름. 여러 번 지정 가능 |
| --test-class CLASS | 실행할 정확한 테스트 클래스 이름. 여러 번 지정 가능 |
| --timeout-ms N | 전체 작업의 협력적 대기 시간 제한. 기본 30,000 ms, 최대 3,600,000 ms |
| --help | 사용법 출력. 종료 0 |

JDK 자체의 고정 파일 검증과 환경 변수 제거는 기존 scripts/java.sh가 담당한다. PitProbe.run을 임의 JVM에서 직접 호출하면 그 JVM의 java.home을 사용하므로 JDK 무결성 고정까지 보장하지 않는다. 자식 명령은 환경을 비우고 임시 HOME과 고정 도구 경로만 전달한다.

후보 수를 N이라고 하면 dry-run과 실제 PIT 실행에 더해 4×N회의 새 컴파일·JUnit 실행이 필요하다. 기본 제한 30초는 작은 프로젝트에서도 부족할 수 있다. 이번 3개 후보의 회귀 시험은 전체 제한을 120,000 ms로 명시했다. 이는 실측 실행 시간이나 범용 권장값이 아니라 시험의 상한이다.

배포 파일은 PIT core·entry·command-line 1.30.0, PIT JUnit5 확장 1.2.3, Commons Text 1.14.0, Commons Lang 3.18.0, JUnit Console 1.10.2다. 파일별 URL·바이트 수·SHA-256·주 라이선스는 [하나의 고정 목록](../src/main/resources/pit-probe-artifacts.tsv)에 있으며 설치기와 실행 검증이 함께 사용한다. JUnit Console은 EPL-2.0, 나머지 주 아티팩트는 Apache-2.0이다. 일부 JAR에 포함된 ASM·jopt-simple 등의 라이선스 고지는 원본 JAR 안에 그대로 보존하며, 주 라이선스 표기가 내부 구성요소의 개별 조건을 없애지는 않는다. 외부 배포물 문서이므로 한 달 뒤 재확인한다.

## 결과를 읽는 방법

Value.positive의 value > 0을 검사하는 실제 시험에서 1·0·-1을 확인하는 강한 테스트는 후보 3개 모두 KILLED였다. 1만 확인하는 약한 테스트는 KILLED 1개, SURVIVED 2개였다. 두 경우 모두 certified=false, admission=backendNotAdmitted이고 CLI 종료 값은 6이다. [실행 테스트](../src/test/java/io/github/hwainhwang/sentinel/mutation/PitProbeTest.java), [CLI 테스트](../src/test/java/io/github/hwainhwang/sentinel/cli/PitProbeMainTest.java)

재검사 증거를 추가하기 전의 2026-09-08 scripts/self-crap.sh 실행은 종료 0, 테스트 232개 통과·함수 670개 측정 가능·기준 초과 0개였다. 이번 단계에서는 후보별 실제 재검사와 실패 위치·경로 교체·동적 테스트의 반례를 추가했다.

2026-09-08 12:08 UTC에 완료한 최종 scripts/self-crap.sh는 종료 0이었다. 테스트 260개가 실패·오류·건너뛰기 없이 통과했고, 생산 코드 함수 724개 모두 CRAP 측정 가능·기준 초과 0개였다. 첫 전체 검사에서 새 람다 2개의 coverage 연결 실패를 확인해 이름 있는 연결 객체·메서드로 바꿨으며, 기준·측정 범위·coverage 연결 규칙을 낮추지 않았다. 후보별 계획 연결, 실제 런타임 예외, 번갈아 바뀌는 실패 위치, 예상 밖 프로세스 종료, 요청·이벤트 경로 교체와 중첩 실패의 반례를 검사했다. 별도 검토자는 코드와 테스트를 읽었고 테스트를 대신 실행하지 않았다.

backendStatus와 backendCounts는 여전히 PIT 원래 관측값이다. 새 replays 배열은 후보별 증거 검사 결과이며 strictKillRate나 인증 판정은 아니다. 원래 KILLED인 후보는 대조·재실행이 일치하는 승인된 단언 종류일 때만 KILLED로 표시한다. 런타임 예외로 PIT가 검출한 후보는 RUNTIME_ERROR, 두 실행의 실패 위치가 다르면 TOOL_ERROR다. 원래 SURVIVED이고 두 변이 실행이 통과하면 SURVIVED다. 다른 PIT 상태는 원래 관측값을 보존하되 현재 재검사 결과에서는 TOOL_ERROR로 남긴다. 기존 [보고서 adapter](pit-report-adapter.md)의 proof 없는 KILLED 정규화 거부는 그대로다.

각 실행은 inventorySha256(전체 테스트 목록), resultsSha256(테스트·컨테이너별 종료 결과), inputSha256(소스와 실제 실행 클래스), originalClassesSha256(변경 전 클래스 집합), mutantClassSha256(변이 클래스), bindingSha256(계획·후보·역할·입력 연결)을 출력한다. 정상 대조의 mutantClassSha256은 빈 값이다. 기존 서명된 JUnit v2 이벤트를 바꾸지 않고, 동일 nonce·입력·이벤트 지문에 연결한 인증 파일을 별도로 만든다. 이 인증은 파일 혼합을 탐지하는 장치이며 같은 권한으로 실행되는 악성 테스트에 대한 신뢰 경계는 아니다.

새 실행기의 비공개 JUnit 이벤트에서 sourceSha256 필드에는 단일 소스 파일이 아니라 위 bindingSha256을 넣는다. 새 프로필의 부모 실행기가 그 값을 검사하고 소스·클래스 지문은 결과의 별도 필드에 보존한다. 따라서 이 내부 이벤트를 기존 단일 소스용 검증기에 그대로 넘기거나, 외부 XML과 임의로 조합한 증거로 사용하면 안 된다. 기존 mutate4java 실행기의 이벤트 생성·검증 의미는 바꾸지 않았다.

출력에는 원시 소스, XML, 테스트 실패 메시지, killingTest, 절대 프로젝트 경로를 싣지 않는다. 도구 stdout·stderr는 버리고 오류는 pitProbeCompileFailed 같은 짧은 고정 코드로 출력한다. 입력 오류는 종료 3, 컴파일·정상 테스트 실패는 4, 배포 파일 검증 실패는 5, 시간 제한은 7, 취소는 8이다. 다른 실행·보고서 오류는 6이며 JSON 결과가 없으므로 성공 관측과 구별된다.

## 안전장치의 한계와 다음 작업

XML은 최대 8 MiB이며 실행 중 50 ms 간격으로 크기를 확인하고 읽을 때 다시 제한한다. 디스크 전체 할당량이나 강제 실시간 상한은 아니다. 소스 읽기·복사·검증에는 취소와 기한을 확인하고, 자식 프로세스 대기는 제한한다. 운영체제의 단일 파일 I/O나 정리 작업은 기한을 넘길 수 있다.

각 명령은 새 Linux 프로세스 그룹에서 실행하고 정상 종료·시간 초과·스레드 취소에 같은 그룹을 종료한다. 일반 자식 프로세스가 뒤늦게 파일을 쓰지 못하는 것을 검사했다. 이는 운영체제 샌드박스가 아니다. 같은 사용자 권한의 테스트는 네트워크나 절대 경로에 접근할 수 있고, 스스로 다른 세션으로 벗어난 프로세스까지 격리한다고 보장하지 않는다. 신뢰할 수 없는 저장소 실행에는 별도 컨테이너·파일시스템·네트워크 격리가 필요하다.

정상 반환·예외·스레드 취소 때 자신이 만든 임시 복사본만 삭제한다. JVM 종료 훅은 자식 그룹 종료를 시도하지만 SIGTERM 중의 임시 폴더 정리, SIGKILL·시스템 장애 뒤 잔여 파일 회수는 아직 보장하지 않는다. 도구 파일 훼손은 자동 덮어쓰기하지 않고 설치·실행을 거부한다.

테스트가 요청 파일의 부모 폴더를 외부 링크로 바꿔도 외부 파일을 지우지 않도록 요청 개별 삭제를 제거했다. 이벤트는 루트부터 디렉터리 핸들을 열고 각 단계에서 링크를 거부해 0600 권한의 새 파일로만 생성한다. 외부 폴더에 이벤트 2개가 생기는 기존 반례를 재현한 뒤 생성 0개로 수정했다. [경로 반례](../src/test/java/io/github/hwainhwang/sentinel/mutation/PitReplayEventPathTest.java), [안전한 기록](../src/main/java/io/github/hwainhwang/sentinel/mutation/junit/JUnitPrivateFiles.java)

실패 지문은 상속받은 실제 선언 메서드까지의 클래스·메서드·줄 번호와 묶인 실패의 자식 위치를 포함한다. 같은 메시지라도 다른 줄의 실패는 구분하지만, 같은 줄에 쓰인 동일한 단언 두 개의 명령어 위치까지 구분하는 것은 아니다. 디버그 위치가 없거나 동일 실패를 입증하지 못하면 검출 증거로 인정하지 않는다. 바이트코드 생성의 전후 기한 확인도 부모 JVM의 CPU 작업을 운영체제가 강제로 중단한다는 의미는 아니다.

묶인 실패의 내부 예외도 기존 MutationNormalizer의 승인된 종류 목록으로 검사한다. 지원하지 않는 AssertionError 하위 종류를 assertAll로 감싸도 외형만 보고 검출 성공으로 승격하지 않는다. 빈 묶음·누락된 예외·16단계를 넘는 중첩도 실행 오류다. 수집기와 정규화기가 하나의 목록을 사용하며, 종류가 승인됐다는 사실만으로 검출 성공을 인정하는 것은 아니다. [실제 JUnit 반례](../src/test/java/io/github/hwainhwang/sentinel/mutation/junit/SentinelTestExecutionListenerIntegrationTest.java)

다음 단계는 실제 프로젝트에서 기존 mutate4java와 나란히 비교, 프로젝트 빌드 프로필 지원, 운영체제 격리·자원 제한·잔여 파일 회수 정책이다. 그 뒤 버전 업데이트 시험을 고정 JAR·공식 API·CLI/XML 호환성 검증에 한정하고 설치 plugin을 기존 CLI 위에 연결한다. 6개월 뒤에도 외부 규칙의 변경이 공통 결과 의미를 바꾸지 않도록 원래 관측과 재검사 증거를 분리한다. 문제가 생기면 실험 CLI만 중단할 수 있도록 기본 backend와 인증 판정을 유지한다.
