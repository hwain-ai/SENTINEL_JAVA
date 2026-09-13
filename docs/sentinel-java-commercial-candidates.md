# Java 상용 변이 시험 후보 재확인

결론: Certitude를 Java용 상용 도구로 확정할 근거는 없으며, Java 상용 확장 후보는 ArcMutate를 별도로 검토하되 현재 PIT 비교 구현은 유지한다.

확인일: 2026-09-09. 이 문서는 공식 자료 조사이며 제품 구매·설치·계정 생성·외부 코드 전송 또는 실제 성능 비교 결과가 아니다.

## 확인한 제품과 확인하지 못한 범위

|후보|공식 자료에서 확인한 내용|SENTINEL에서의 판단|
|---|---|---|
|Synopsys Certitude|Synopsys의 2013년 기술 자료는 반도체 RTL 설계 코드에 결함을 넣어 검증 환경이 탐지하는지 시험하는 제품으로 설명한다.|이 자료로 Java·JUnit·Maven 지원을 주장할 수 없다. 사용자가 지칭한 같은 이름의 다른 제품이 있다면 정확한 URL과 배포 자료가 필요하다.|
|Synopsys Testbench Quality Assurance|현재 공식 지원 설명에는 VHDL, Verilog, SystemVerilog, SystemC, C/C++와 소프트웨어·스크립트 testbench가 나온다.|열거된 언어에서 Java는 확인되지 않는다. 이 목록의 부재만으로 모든 비공개 지원 가능성까지 부정하지는 않는다.|
|ArcMutate|공식 사이트가 Java·Kotlin·Android용 PIT 확장 제품을 소개하고 상용 요금제를 제공한다. 문서는 PIT를 먼저 설정한 다음 별도 확장 plugin을 추가하는 구조를 명시한다.|Java 상용 후보로 식별 가능하다. 현재 PIT lane 위에 별도 version·artifact·license 조건을 갖는 선택 확장으로 검토할 수 있다. 실제 지원 승인은 아직 없다.|

Certitude의 설명 근거: [Synopsys Advanced Verification Bulletin, Issue 2, 2013](https://www.synopsys.com/content/dam/synopsys/company/publications/advanced-verification-bulletin/advanced-verification-bulletin-issue2.pdf). 현재 지원 환경 목록의 근거: [Synopsys Testbench Quality Assurance](https://www.synopsys.com/verification/simulation/testbench-quality-assurance.html). Java 상용 제품과 확장 구조의 근거: [ArcMutate 공식 사이트](https://www.arcmutate.com/), [ArcMutate Plugins for Pitest](https://docs.arcmutate.com/).

## 선택지와 구현에 미치는 영향

|선택지|즉시 필요한 작업|장점과 비용|
|---|---|---|
|기존 PIT 공개 참조 비교 계속|고정 Maven 원래 빌드와 전체 테스트를 지원하는 PIT 실행 경계 검증|이미 정한 비교 작업을 진행한다. 상용 확장의 추가 효과까지 입증하지는 못한다.|
|ArcMutate를 상용 추가 후보로 검토|선택할 확장, 배포 좌표·버전·지문, 라이선스, PIT 호환 조합과 출력 계약 확인|PIT의 빌드 연결을 재사용할 가능성이 있다. 추가 변이 규칙 때문에 후보 수가 달라지므로 기존 PIT 결과와 수치만 직접 비교하면 안 된다.|
|별도 Java Certitude 제품 자료 확인|공식 URL과 Java 실행 방식부터 확인|사용자가 의도한 다른 제품이면 정정할 수 있다. 자료가 없는 상태에서 빈 adapter나 성공 상태를 만드는 것은 검증이 아니다.|

추천은 첫 번째 작업을 계속하면서 두 번째를 선택 가능한 후보로 기록하는 것이다. 이는 제품 선택 제안이며 상용 도구 구매나 기존 기본 backend 변경 승인이 아니다. 세 번째 제품의 정확한 자료가 없어도 현재 Go·Java 공개 도구 검증을 멈출 이유는 없다.

다음 순서는 원래 Maven 빌드 검증, PIT 비교, 선택한 상용 확장의 독립 검증, 검증된 조합만 공통 CLI로 연결이다. 6개월 뒤 상용 확장의 버전이나 라이선스 조건이 바뀌면 해당 조합만 다시 검증하고 기존 PIT 조합을 유지할 수 있어야 한다. Codex·Claude Code 플러그인에는 상용 제품별 실행 로직을 복사하지 않는다.

## 변경 기록

- 2026-09-09: Certitude의 Java 제품 분류를 확정하지 않도록 공식 자료 근거를 추가하고 ArcMutate를 조사 후보로 구분했다. 코드·잠금 버전·계정·유료 서비스·기본 backend는 변경하지 않았다.
