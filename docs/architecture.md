# Java CRAP core 구조

통합 어댑터는 CRAP과 mutation을 기본으로 병렬 실행한다. 요청의 `executionMode`가 `sequential`이면 순차 실행한다. `SelfCrapMain --list-functions`가 커버리지 없이 함수 위치를 구하고, 두 측정은 같은 선택 결과로 시작한다. Mutation은 CRAP 결과를 기다리지 않는다. 함수 이름·같은 행의 중첩 판단은 `CrapGate.selectDefinitions`를 두 경로가 함께 사용한다.

두 측정은 감독 프로세스가 관리하는 별도 프로세스에서 실행되며, 각각 결과 JSON과 로그를 쓴다. 실행 오류·취소 시 형제 작업을 중단하고 자식 프로세스를 회수한 뒤 임시 폴더를 정리한다. 프로젝트의 `.sentinel-m2`는 복사하지 않고 함께 참조하며, 검사 전후 원본 파일 지문이 다르면 결과를 거부한다.

## 한눈에 보기

`JavaAnalyzer`가 source callable과 CC를 만들고, `JacocoCoverage`가 정확히 일치하는 instruction coverage만 붙입니다. `ExactCrap`과 `CanonicalDecimal`은 그 결과를 정수 연산으로 계산하며, `CrapRows`가 모든 runtime이 공유하는 순서로 정렬합니다.

## Source 경계

입력은 project-relative POSIX path와 raw UTF-8 bytes입니다. Decoder는 잘못된 UTF-8을 거부하고 UTF-8 BOM만 parser text에서 제외합니다. Source position은 JDK compiler의 UTF-16 character offset을 원본 raw bytes의 offset으로 다시 바꿉니다. 따라서 CRLF나 한글이 있어도 range는 원본의 정확한 half-open byte slice입니다.

Compiler task는 annotation processing을 끄고 parse와 semantic analysis만 수행합니다. `generate`를 호출하지 않으므로 분석 대상의 class initializer나 method를 실행하지 않습니다.

## Callable과 complexity 소유권

명시적으로 작성된 method, constructor와 lambda가 각각 하나의 callable입니다. Named method ID는 module-relative path, binary owner name, kind, source name과 exact JVM descriptor를 versioned SHA-256 descriptor로 만들며 overload를 합치지 않습니다. Enum과 비정적 member class constructor에는 compiler가 더하는 parameter도 descriptor에 포함합니다. Capture parameter가 달라질 수 있는 local constructor는 classfile 확인 전까지 coverage를 unknown으로 둡니다. Lambda site는 enclosing callable ID, canonical target type, binding·callee·argument·branch 같은 positionless ancestor role, arity와 expression/block kind를 묶습니다. Lambda body statement, line, byte 위치와 sibling ordinal은 ID에 넣지 않습니다. 이 정보까지 같은 두 lambda는 임의 번호를 붙이지 않고 `identityAmbiguous`로 거부합니다.

CC는 기본 1에 `if`, 두 `for`, `while`, `do-while`, `catch`, ternary, non-default switch case, `&&`, `||`를 더합니다. Nested class, method와 lambda body는 parent scanner가 들어가지 않아서 child decision이 중복되지 않습니다.

## Coverage 경계

JaCoCo XML은 DTD와 external entity를 막고, 허용하지 않은 element 배치, 잘못된 JVM descriptor, 음수와 안전 정수 범위 밖 counter를 거부합니다. Join key는 internal class name, exact method name과 JVM descriptor이며, classfile의 첫 instruction line이 현재 callable source line 범위 안에 있어야 합니다. 가까운 줄이나 같은 이름으로 대신 연결하지 않습니다. Missing, stale 또는 duplicate method, missing/duplicate instruction counter와 total 0은 각각 명시적인 unknown입니다. Source만으로 synthetic lambda method를 증명할 수 없으므로 lambda도 unknown입니다.

검사기 자체 코드에도 같은 규칙을 적용합니다. 자체 CRAP 검사 대상의 선택 필터와 결과 변환은 이름이 있는 메서드·반복문·메서드 참조로 작성합니다. 람다를 측정에서 제외하거나 가까운 메서드의 커버리지를 대신 붙이지 않습니다.

## Exact 결과

CRAP numerator와 denominator는 `BigInteger`로 계산한 뒤 GCD로 줄입니다. Gate는 decimal을 만들기 전에 exact fraction을 지정한 상한(기본 8)과 비교합니다. Decimal은 12자리 round-half-to-even이며 locale formatter나 binary floating point를 사용하지 않습니다. Stable sort는 unknown-first, exact risk descending, UTF-8 path bytes, source byte offset, UTF-8 callable ID 순서입니다.

## Toolchain 경계

`toolchain.lock.json`이 JDK와 Maven 버전·아카이브·실행 파일·설치 트리 지문을 정합니다. `scripts/toolchain.py`는 잠금과 실제 파일을 비교한 뒤 최소 환경으로 자식 프로세스를 실행합니다. `toolchain_lock.py --print-tree-digest`는 잠금 검토용 지문을 계산합니다. 도구 설치·임시 HOME·Maven 캐시·다운로드 캐시는 현재 사용자만 접근하는 `0700` 폴더를 사용합니다. 다른 버전이나 지문이 다른 설치는 실행하지 않습니다.

## 선택 검사

통합 어댑터는 기능 파일·함수와 테스트 파일 선택을 native 명령으로 전달합니다. CRAP은 전체 분석 결과에서 지정한 파일·함수를 판정하고, mutation은 선택한 위치의 후보만 실행합니다. 함수 이름이 모호하면 정확한 callable ID를 사용합니다. 변이 backend가 행 단위 선택을 사용하므로 같은 행에 다른 메서드가 겹치면 함수 선택을 거부합니다.

선택한 테스트 파일은 Maven 테스트 클래스 이름으로 변환합니다. 테스트를 생략하면 Maven 기본 탐색을 사용합니다. 기본 검사에는 자동 실행 시간 제한이 없으며, 취소 시 실행 중인 자식 프로세스를 정리합니다. 결과는 함수별 CRAP, 파일·함수별 mutation 비율과 변이 위치·상태를 담습니다.

## 별도 PIT 명령

`PitProbeMain`은 제한된 Java 소스 프로젝트를 위한 실험용 실행기입니다. 기본 mutate4java 검사와 별도로 실행하며 결과는 `certified: false`, `backendNotAdmitted`, 종료 6입니다. 원래 프로젝트의 Maven·Gradle 설정은 실행하지 않습니다.

저장소 도구를 준비한 뒤 `scripts/bootstrap-pit-probe.sh`로 PIT 파일을 설치하고 `scripts/mvn.sh test`로 클래스를 준비합니다. 실행 형식은 다음과 같습니다.

```sh
# 고정 Java로 별도 PIT 명령 실행; 뒤 인자는 프로젝트·도구·클래스 선택
scripts/java.sh -cp target/classes io.github.hwainhwang.sentinel.cli.PitProbeMain \
  --project /path/to/project --artifacts /path/to/SENTINEL_JAVA/.toolchain/pit-probe \
  --target-class example.Pricing --test-class example.PricingTest
```

대상과 테스트 클래스는 정확한 이름으로 각각 최대 64개까지 반복 지정합니다. `--timeout-ms`는 이 별도 명령의 전체 협력적 대기 제한이며 기본 30,000 ms, 최대 3,600,000 ms입니다. 통합 기본 검사의 시간 제한 정책과 다릅니다. `--help`는 도움말만 출력합니다.

PIT 명령은 Java 17·JUnit 5·표준 `src/main/java`와 `src/test/java` 배치를 사용합니다. 추가 프로젝트 의존성, 리소스, `module-info.java`, annotation processor, 다른 JVM 언어는 지원하지 않습니다. 일반·상속 테스트와 동적으로 등록되는 JUnit 테스트를 실행하되 대조 실행의 목록이 같아야 합니다. 건너뛴 테스트도 목록에 포함하며, 실행된 테스트가 없으면 성공으로 세지 않습니다. 묶인 바이트코드 후보는 `pitReplayGroupedUnsupported`로 거부합니다.

연산자는 `CONDITIONALS_BOUNDARY`, `NEGATE_CONDITIONALS`, `TRUE_RETURNS`로 고정됩니다. 독립 후보 탐색 결과를 실행 결과와 대조하고, 후보마다 정상 코드 두 번과 변이 코드 두 번을 새 사본에서 실행합니다. 같은 기대값 검사 실패를 확인한 경우만 탐지 증거를 만듭니다. 소스는 파일당 4 MiB·합계 16 MiB·256개 이하이며 원본과 도구 파일의 변경을 확인합니다. 프로세스 정리는 운영체제 샌드박스를 대신하지 않으므로 신뢰할 수 있는 프로젝트에서 사용합니다.

PIT·JUnit 파일의 버전·크기·SHA-256·라이선스는 [고정 아티팩트 목록](../src/main/resources/pit-probe-artifacts.tsv)에 있습니다. 설치 파일에 포함된 제3자 라이선스 고지도 함께 보존해야 합니다.

## PIT XML 연결

`PitReportAdapter`는 8 MiB 이하, 요소 깊이 4 이하의 일반 XML 보고서를 읽습니다. DTD·외부 참조·XInclude, 알 수 없는 필드, 중복 변이와 상태 불일치를 거부하며 full mutation matrix 형식은 지원하지 않습니다.

변이 식별자는 클래스·메서드·JVM 서명·연산자·명령어 인덱스입니다. 실행 전 확보한 후보 목록과 결과를 정확히 대조합니다. XML의 `KILLED` 표시나 `killingTest` 문자열만으로 탐지 성공을 인정하지 않고 `MutationProof`로 정상 대조·반복 실패를 확인합니다.

| PIT 상태 | SENTINEL 상태 |
|---|---|
| `KILLED` | 증거 검증 후 결정; 증거가 없으면 `pitProofMissing` |
| `SURVIVED`, `NO_COVERAGE` | `survived`, `uncovered` |
| `TIMED_OUT` | `timedOut` |
| `NON_VIABLE` | `toolError` |
| `MEMORY_ERROR`, `RUN_ERROR` | `runtimeError` |
| `NOT_STARTED`, `STARTED` | `pending` |
| `EQUIVALENT` | `ignored` |

구현과 시험은 [PitReportAdapter](../src/main/java/io/github/hwainhwang/sentinel/mutation/PitReportAdapter.java), [PitProbe 시험](../src/test/java/io/github/hwainhwang/sentinel/mutation/PitProbeTest.java)에 있습니다.
