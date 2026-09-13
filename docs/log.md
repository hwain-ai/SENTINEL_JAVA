# 변경 기록

## 2026-09-13

- **Update** scripts/toolchain.py(신규)·toolchain_lock.py·toolchain.lock.json·sentinel-tool/·src/test/.../ToolchainLauncherTest.java: Linux 전용 bash 실행기(bootstrap-toolchain.sh·bootstrap-backends.sh·bootstrap-m2.sh·bootstrap-pit-probe.sh·mvn.sh·java.sh·doctor.sh·setup.sh 본문)를 표준 라이브러리만 쓰는 Python 실행기 하나로 바꿨다. 잠금 파일의 java 항목은 `platforms`(linux-x86_64·linux-aarch64·darwin-x86_64·darwin-aarch64) 아래에 Temurin 아카이브의 공식 주소·크기·SHA-256·실행 파일·설치 트리 지문과 macOS 묶음의 JAVA_HOME 위치(`Contents/Home`)를 담고, Maven 은 같은 tarball 이라 항목 하나를 유지한다. 네 플랫폼의 JDK 지문은 각 아카이브를 받아 같은 추출 규칙(디렉터리 755, 파일은 아카이브 모드에서 그룹·타인 쓰기 비트만 제거해 JDK 의 읽기 전용 444 파일 유지, 심볼릭 링크 모드는 상수)으로 재서 넣었고 linux-x86_64 값은 이전 잠금과 같다. 변이 백엔드 jar 는 잠긴 소스에서 그 플랫폼의 javac 로 컴파일하며 지문 117ef17d… 이 재현된다. Maven 인자는 닫힌 집합(-o·-B·-ntp·-q·-e·-V·-v 계열, clean·compile·test·package·verify, `-Dtest=`·`-Dit.test=` 식별자 목록)만 통과시키고, mvn·java 자식은 이전 wrapper 와 같이 umask 022, 실행기 자신의 준비물(downloads·backends·m2·pit-probe)은 077 로 만든다. 어댑터는 실행기의 `paths` 명령으로 그 플랫폼의 JAVA_HOME·MAVEN_HOME 을 얻는다(어댑터 0.1.2). 이전 bash 의 "직접 실행 봉인"(SENTINEL_JAVA_SEALED_ENTRY, /proc 검사)은 Python 실행기에서는 `-I -B` 격리와 자식의 빈 환경으로 대신하며, 시험은 호출자의 JAVA_TOOL_OPTIONS·MAVEN_OPTS 가 자식에 닿지 않는 것과 빈 환경에서 `/bin/sh scripts/mvn.sh` 가 동작하는 것으로 그 경계를 확인한다. 검사기 자체 품질 점검(self-crap.sh·self-mutation-slice.sh·typed-mvn-test.sh)은 Linux 전용 bash 로 남아 있다.
- **Fix** mutation/PitProbeProcess.java·ProjectSnapshot.java·ProjectMutationRunner.java·PitProbeWorkspace.java·pom.xml·sentinel-tool/sentinel-tool: macOS 에서 막히는 두 곳을 고쳤다. (1) PIT 탐침 자식을 `/usr/bin/setsid --wait` 와 `kill -- -pgid` 로 묶어 정리했는데 macOS 에는 setsid 가 없다. TypedMavenRunner 와 같이 자식의 후손 프로세스를 깊은 것부터 강제 종료하는 방식으로 바꿨다. (2) macOS 의 임시 폴더는 심볼릭 링크인 /var 아래에 있어 실행기들의 실제 경로 대조(projectRootInvalid 류)에 걸린다. 검사기가 만드는 임시 루트(변이 스냅샷·이벤트 폴더·PIT 작업 폴더)와 어댑터의 작업 폴더는 만든 직후 실제 경로로 바꾸고, 자체 시험의 임시 폴더는 surefire 인자로 `target/` 아래에 두도록 했다.
- **Update** scripts/verify_repository.sh 삭제·BackendBootstrapTest: 계정 이름 변경 전 git 신원과 "원격 없음" 을 요구하던 저장소 점검 스크립트는 공개 저장소 상태와 맞지 않고 아무 데서도 부르지 않아 지웠다. bash 로 직접 부르면 거부하던 봉인 시험은 얇은 wrapper 에서는 의미가 없어 지웠다.
- **Fix** cli/MutationCommandMain.java·crap/CrapGate.java·mutation/ProjectMutationRunner.java·junit/SentinelTestExecutionListener.java·scripts/self-mutation-slice.sh: 오늘 앞서 넣은 기준값·변경분 코드가 검사기 자체 CRAP 게이트를 넘지 못하고 있었다(`scripts/self-crap.sh` 종료 2: 상한 초과 4개, 측정 불가 1개). 인자 쌍 읽기와 변경 경로 검증(복잡도 8)은 옵션 저장과 경로 조각 판정을 함수로 나눴고, `--only` 걸러내기 람다는 JaCoCo 매핑이 안 잡혀 이름 있는 반복문으로 바꿨으며, 변이 대상 좁히기(복잡도 7, 시험 없음)는 단순화하고 직접 시험 3개를 붙였고, JUnit 리스너 요약(복잡도 8)은 완전성 판정을 함수로 뺐다. 결과 750개 전부 측정 가능·초과 0. 자체 변이 조각 시험은 ExactCrap.java 의 `decimal()` 이 기준값 작업으로 58행에서 64행으로 옮겨져 변이 0개였던 것을 64행으로 맞췄다(KILLED 1/1).
- **Update** .github/workflows/ci.yml: ubuntu-latest·ubuntu-24.04-arm·macos-15·macos-15-intel 네 플랫폼에서 `sentinel-tool/setup.sh` 로 준비하고 자체 시험 전체를 오프라인으로 돌린다.
- **Creation** scripts/bootstrap-m2.sh·.github/workflows/ci.yml: 새 clone 에서는 잠긴 오프라인 Maven 저장소(`.toolchain/m2`)가 비어 있어 `sentinel-tool/setup.sh` 의 오프라인 compile 이 실패했다. bootstrap-m2.sh 가 검사기 자체 POM 을 한 번 온라인으로(compile·시험 1개·package) 해석해 저장소를 채우고 표시 파일을 남기며, setup.sh 가 backends 준비 뒤에 이를 부른다. GitHub Actions 워크플로가 push·PR 마다 bootstrap 4단계 뒤 `mvn.sh -o test` 로 자체 시험 전체를 돌린다(`.toolchain` 은 잠금 파일 지문으로 캐시).
- **Update** scripts/mvn.sh·sentinel-tool/·mutation/ProjectSnapshot.java·mutation/junit/SentinelTestExecutionListener.java: 공개 프로젝트(Apache Commons CLI 1.10.0)를 검사하기 위한 변경. `mvn.sh deps <프로젝트>` 가 SENTINEL 소유 파일을 뺀 사본에서 프로젝트의 기본 시험 빌드를 온라인으로 한 번 돌려 `<프로젝트>/.sentinel-m2` 를 채우고, 어댑터는 그 폴더가 있으면 검사기의 잠긴 저장소 대신 그것을 오프라인 저장소로 쓴다. CRAP 사본과 변이 스냅샷은 `.sentinel-m2`·`.sentinel-tools` 폴더와 루트의 sentinel.workspace.json·sentinel.config.json 을 복사하지 않아 프로젝트의 라이선스 검사(apache-rat)가 원본 트리만 본다. JUnit 리스너는 건너뛴 테스트(@Disabled, 비활성 중첩 클래스)를 실패가 아니라 재고(inventory)의 일부로 기록해 두 대조 실행이 같은 집합을 건너뛰어야 하고, 실행된 테스트가 하나도 없으면 여전히 거부한다. 또한 @ParameterizedTest·@TestFactory 처럼 실행 중에 등록되는 테스트(dynamicTestRegistered)를 재고에 넣어 허용한다. 이전에는 메서드 기반 컨테이너를 모두 거부해 Commons CLI(968개 테스트, @Disabled 61개, 매개변수 테스트 다수)의 대조 실행이 mutationControlInvalid 로 끝났다. 가정 실패로 중단(ABORTED)된 테스트는 여전히 실행 오류로 본다. 어댑터 버전 0.1.1.
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
