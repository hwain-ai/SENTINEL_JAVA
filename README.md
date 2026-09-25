# SENTINEL_JAVA

Java 함수의 복잡도와 JaCoCo 실행 범위로 CRAP을 계산하고, mutate4java와 JUnit 실행 기록으로 테스트의 오류 탐지율을 측정합니다. `sentinel-tool/`의 어댑터가 Maven 테스트·CRAP·변이 검사를 연결합니다.

## 검사 실행

[SENTINEL 설치 안내](https://github.com/hwain-ai/SENTINEL)를 따라 통합 명령을 준비한 뒤 검사할 프로젝트에서 실행합니다.

```sh
# Java 검사 도구와 프로젝트 설정 준비
sentinel setup --language java
# 설치된 도구의 버전과 상태 확인
sentinel version
# 기능 파일의 메서드를 지정한 테스트로 검사
sentinel check --file src/main/java/example/Pricing.java --function calculateDiscount --tests src/test/java/example/PricingTest.java
# 프로젝트 설정의 기능 코드와 테스트 전체 검사
sentinel check --all
```

`--file`은 점수를 측정할 기능 파일, `--function`은 괄호 없는 메서드 이름입니다. 함수를 생략하면 파일 전체를 측정합니다. 같은 이름이 여러 개면 정확한 callable ID를 사용합니다. 변이 도구가 행 단위로 선택하므로 다른 메서드가 같은 행에 겹치면 함수 선택을 거부합니다. 이때는 파일 전체를 검사합니다.

`--tests`는 실행할 테스트 파일입니다. 여러 파일은 옵션을 반복하며, 생략하면 Maven의 기본 테스트 탐색을 사용합니다. `--changed`는 Git 변경분의 기능 코드만 선택합니다. 테스트만 바뀌었으면 기능 파일을 직접 지정해 재검사합니다.

기본 검사에는 자동 실행 시간 제한이 없습니다. Ctrl+C로 중단합니다. 통합 명령에서 `exitCode`는 명령 종료 코드, `selection`은 검사 범위, `results[].status`는 품질 판정입니다. 내부 CRAP·mutation의 `pass`는 각 기준 충족 여부입니다. [JSON 조각별 결과 해석](https://github.com/hwain-ai/SENTINEL/blob/main/docs/results.md)을 참고하세요.

## 지원 범위와 제한

프로젝트의 Maven 의존성은 `sentinel setup --language java --java-dependencies`로 `.sentinel-m2`에 먼저 준비합니다. 두 검사는 이 저장소를 참조하며 패키지를 각 사본에 복사하지 않습니다. 프로젝트 저장소가 없으면 검사기의 `.toolchain/m2`를 사용합니다. 검사 중에는 오프라인으로 실행하므로 필요한 패키지가 없으면 실패합니다.

통합 `check`의 기본값은 CRAP·mutation 병렬 실행입니다. `--execution-mode sequential`로 순차 실행합니다. 함수 선택은 소스 분석으로 검사 전에 끝내므로 mutation이 CRAP 결과를 기다리지 않습니다. 각 작업은 별도 프로세스·임시 폴더·로그를 사용하며, CRAP 사본은 CRAP 측정이 끝나면 삭제합니다. 실행 오류나 취소가 발생하면 다른 작업도 중단·정리하고, 점수 미달이면 두 결과를 함께 보고합니다.

분석기는 annotation processor를 끄고 소스 분석만 합니다. JaCoCo의 클래스·메서드·JVM 서명이 모두 같은 실행 범위만 연결하며, 누락·중복·확인할 수 없는 lambda는 미측정으로 표시합니다. CRAP 기본 상한은 8, mutation 최소 탐지율은 90%입니다.

Linux와 macOS의 x86_64·arm64를 지원하며 Windows는 WSL2에서 사용합니다. JDK·Maven 버전과 설치 파일은 `toolchain.lock.json`으로 고정합니다. 전체 Maven dependency 잠금은 제공하지 않습니다.

별도 `PitProbeMain`은 제한된 프로젝트를 위한 실험용 PIT 명령입니다. 사용법·지원 제한·XML 계약은 [분석과 실행 구조](docs/architecture.md)에 있습니다.

## 검사기 개발과 검증

저장소 루트에서 실행합니다.

```sh
# 고정 JDK·Maven·backend·오프라인 Maven 저장소·컴파일 준비
sentinel-tool/setup.sh
# 검사기 자체 테스트
scripts/mvn.sh -o test
# 전체 테스트와 검사기 자체 CRAP 판정
python3 -I -B scripts/toolchain.py self-crap
# 변이 한 개를 반복 실행해 탐지 증거 확인
python3 -I -B scripts/toolchain.py self-mutation-slice
```

CI는 일반 테스트에 이어 `self-crap`과 `self-mutation-slice`를 실행합니다. 일반 테스트 통과만으로 CI 검증이 끝난 것은 아닙니다. `SelfQualityTest`는 복잡도와 함께 모든 측정 대상 함수가 커버리지에 연결될 수 있는지도 확인합니다.

현재 명령은 `SelfCrapMain`(CRAP)과 `MutationCommandMain`(변이)입니다. `--crap-max`와 `--mutation-min`은 정수 또는 소수점 두 자리까지의 문자열로 받습니다. [문서 목록](docs/index.md)에서 관련 계약과 개발 안내를 찾을 수 있습니다.

통합 실행기에 연결하는 어댑터 버전은 `0.1.6`이다. [sentinel-tool/version](sentinel-tool/version)과 설치한 실행기의 승인 목록을 함께 확인한다. 기존 설치의 갱신은 [통합 실행기 갱신 안내](https://github.com/hwain-ai/SENTINEL#승인된-도구-버전-갱신)를 따른다. 이 버전은 기본 병렬 실행과 `--execution-mode sequential` 선택을 지원한다.
