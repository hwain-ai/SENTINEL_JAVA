# SENTINEL_JAVA

## 역할

SENTINEL_JAVA의 단일 책임은 Java 프로젝트의 CRAP 계산과 mutation 결과를 하나의 품질 게이트로 판정하는 것입니다.

현재 native Java CRAP library core가 구현되어 있습니다. JDK compiler tree로 method, constructor, lambda를 따로 찾고, callable마다 cyclomatic complexity를 계산합니다. JaCoCo XML은 class name, method name, JVM descriptor가 모두 같은 method의 instruction counter만 결합합니다. CRAP 계산과 정렬은 부동소수점을 사용하지 않습니다.

Coverage process 실행, CLI, mutation backend, 반복 결함 history와 GitHub private remote는 아직 구현하지 않았습니다. Source 분석은 annotation processor를 끄고 parse와 semantic analysis만 수행하며 대상 코드를 실행하지 않습니다.

## 핵심 규칙

- Source는 strict UTF-8 bytes이며 BOM, CRLF와 다중 byte 문자를 보존한 0-based half-open byte range를 냅니다.
- Parent method의 CC에는 nested class와 lambda의 decision을 다시 더하지 않습니다.
- JaCoCo method가 없거나 중복되고, instruction이 0개이거나 lambda synthetic method를 증명할 수 없으면 coverage를 unknown으로 둡니다.
- CRAP은 reduced `BigInteger` fraction으로 계산하고 exact 8 이하만 통과합니다.
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
