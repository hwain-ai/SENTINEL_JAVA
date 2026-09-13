# SENTINEL_JAVA

## 역할

SENTINEL_JAVA의 단일 책임은 Java 프로젝트의 CRAP 계산과 mutation 결과를 하나의 품질 게이트로 판정하는 것입니다.

현재 native Java CRAP library core가 구현되어 있습니다. JDK compiler tree로 method, constructor, lambda를 따로 찾고, callable마다 cyclomatic complexity를 계산합니다. JaCoCo XML은 class name, method name, JVM descriptor가 모두 같은 method의 instruction counter만 결합합니다. CRAP 계산과 정렬은 부동소수점을 사용하지 않습니다.

CLI 진입점은 두 개입니다. `SelfCrapMain`은 JaCoCo XML로 CRAP 판정을, `MutationCommandMain`은 mutate4java와 typed JUnit listener로 변이 판정을 냅니다. 통합 SENTINEL 연결은 `sentinel-tool/` 폴더가 맡습니다. 어댑터가 도구 요청(표준입력 JSON)을 받아 프로젝트 사본에서 Maven 테스트를 JaCoCo agent와 함께 돌리고, XML 보고서로 CRAP 판정을, 원본 위치에서 변이 판정을 차례로 실행한 뒤 두 결과를 합쳐 응답 JSON 하나만 표준출력에 씁니다. `sentinel setup --language java`가 `sentinel-tool/setup.sh`로 JDK·Maven·백엔드·컴파일을 준비한 뒤 이 어댑터를 묶음으로 설치합니다. 반복 결함 history는 아직 없습니다. Source 분석은 annotation processor를 끄고 parse와 semantic analysis만 수행하며 대상 코드를 실행하지 않습니다.

기준값은 `SelfCrapMain`의 선행 인자 `--crap-max`(CRAP 상한, 기본 8)와 `MutationCommandMain`의 `--mutation-min`(변이 최소 kill 비율 %, 기본 100)으로 넘깁니다. 정수 또는 소수점 두 자리까지의 문자열이며 `GateThreshold`가 정확한 분수로 읽어 비교합니다. 증거 계약의 crap·mutation 구성요소도 crapMax·mutationMin을 필수로 담습니다.

변경분만 검사하려면 `SelfCrapMain`에 선행 인자 `--only 경로`(반복)를, `MutationCommandMain`에 `--changed-file 경로`(반복)를 넘깁니다. CRAP은 전체 소스를 분석하되 지정한 파일의 callable만 판정하고, 변이는 지정한 생산 소스만 대상으로 합니다(inventory 밖 경로는 거부). 어댑터는 통합 요청의 changedFiles를 생산 소스와 교차해 넘기며, 교차분이 비면 판정할 대상이 없으므로 아무 검사도 돌리지 않고 통과로 응답합니다.

검사 대상 프로젝트의 Maven 의존성은 이 검사기의 잠긴 `.toolchain/m2`에서만 오프라인으로 해석되므로, 거기 없는 의존성을 쓰는 프로젝트는 아직 검사할 수 없습니다.

## 핵심 규칙

- Source는 strict UTF-8 bytes이며 BOM, CRLF와 다중 byte 문자를 보존한 0-based half-open byte range를 냅니다.
- Parent method의 CC에는 nested class와 lambda의 decision을 다시 더하지 않습니다.
- JaCoCo method가 없거나 중복되고, instruction이 0개이거나 lambda synthetic method를 증명할 수 없으면 coverage를 unknown으로 둡니다.
- CRAP은 reduced `BigInteger` fraction으로 계산하고 상한(기본 8) 이하만 exact 비교로 통과합니다.
- Unknown coverage를 먼저, known coverage는 exact fraction 내림차순으로 정렬합니다.

## 검증

도구 잠금이 완성된 환경에서는 저장소 root에서 아래 명령으로 격리된 JDK와 Maven만 사용합니다.

```text
scripts/bootstrap-toolchain.sh
scripts/mvn.sh -o test
```

현재 Temurin JDK 17.0.20.1+1과 Maven 3.9.16은 archive, 실행 파일, 설치 tree digest까지 잠겨 있습니다. Launcher는 이 값과 version 출력이 모두 맞을 때만 실행합니다. 다만 Maven dependency 전체를 검증하는 `dependency-lock.json`과 JaCoCo 0.8.12는 아직 없으므로, 현재 build는 T17·T18 전체 완료 상태가 아닙니다.

## 설계 근거

원본 작업공간 설계 문서: `docs/design-docs/2026-08-native-quality-tools.md`
