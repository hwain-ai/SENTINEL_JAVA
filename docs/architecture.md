---
type: Architecture Note
okf_version: "0.2"
---

# Java CRAP core 구조

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

## Exact 결과

CRAP numerator와 denominator는 `BigInteger`로 계산한 뒤 GCD로 줄입니다. Gate는 decimal을 만들기 전에 exact fraction을 8과 비교합니다. Decimal은 12자리 round-half-to-even이며 locale formatter나 binary floating point를 사용하지 않습니다. Stable sort는 unknown-first, exact risk descending, UTF-8 path bytes, source byte offset, UTF-8 callable ID 순서입니다.

## Toolchain 경계

`toolchain.lock.json`만 JDK와 Maven identity를 정합니다. Direct launcher의 clean-environment shebang이 `BASH_ENV`를 포함한 inherited option을 제거합니다. Archive, executable과 canonical complete-tree manifest가 모두 잠겨야 bootstrap 또는 launcher가 진행합니다. Manifest digest는 UTF-8 byte로 정렬한 모든 directory, regular file, root 내부 relative symlink의 type, relative path, POSIX mode와 content SHA-256 또는 link target을 length-prefix한 SHA-256입니다. `toolchain_lock.py --print-tree-digest`가 lock 검토용 후보 digest를 계산하고 launcher는 같은 알고리즘으로 전체 tree를 다시 검증합니다. Local toolchain root, synthetic home, Maven cache와 download cache는 owner-only `0700` directory여야 합니다. Partial tree, 변조된 version과 ambient runtime으로는 성공할 수 없습니다.
