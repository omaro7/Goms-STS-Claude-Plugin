# V1.3 테스트용 Maven 샘플

플러그인의 **Compile / Test / Fix with Claude** 버튼을 시험하기 위한 작은 Maven + JUnit 5 프로젝트입니다.
Maven Wrapper(3.3.4, `only-script`)가 들어 있어서 Maven을 따로 설치할 필요가 없습니다. 첫 빌드 때 Maven 3.9.16과 의존성을 인터넷에서 내려받습니다.

## 준비

1. 런타임 워크벤치의 Claude Code Chat에서 **Workspace root**를 이 폴더로 지정합니다.
   `D:\04.GomsBook-AI\Goms-STS-Claude-Plugin\poc\v1.3-maven-sample`
2. (선택) `File → Import → Maven → Existing Maven Projects`로 가져오면 Problems 뷰 표시와 에디터 줄 이동까지 확인할 수 있습니다. 가져오지 않아도 빌드와 Fix with Claude는 동작합니다.
3. `JAVA_HOME`이 JDK 17 이상을 가리키는지 확인합니다. 설정이 없으면 플러그인이 STS가 쓰는 JDK를 사용합니다.

## 시나리오

| # | 할 일 | 기대 결과 |
|---|---|---|
| 1 | **Compile** | 기록창 `[BUILD] BUILD SUCCESS`, Console에 Goms Maven 로그 |
| 2 | **Test** | `tests 4 run` 성공 |
| 3 | `Calc.java`의 `return a + b;`를 `return a + c;`로 바꾸고 저장 → **Compile** | `BUILD FAILURE · 1 compile error`, Problems 뷰에 `[mvn] cannot find symbol` (한국어 JDK라면 `심볼을 찾을 수 없습니다`) |
| 4 | **Fix with Claude** | Edit 모드로 전환 → 수정 diff 승인 → 빌드 실행 승인 → `[BUILD by Claude] BUILD SUCCESS` |
| 5 | `CalcTest`의 `assertEquals(5, ...)`를 `assertEquals(6, ...)`로 바꾸고 저장 → **Test** | `tests 4 run, 1 failed`, Problems 뷰에 `[mvn test] CalcTest#add failed: expected: <6> but was: <5>` |
| 6 | **Fix with Claude** | Claude가 테스트가 틀렸다고 설명하고 테스트를 고쳐도 될지 묻는지 확인 (Fix 프롬프트의 규칙) |
| 7 | Edit 모드에서 "average가 null 요소를 건너뛰게 하고 테스트도 추가해줘" | 수정 승인 → `run_maven test` 승인 → 통과 |

빌드 결과물은 `target/`에 생기며 git에서는 무시됩니다(`.gitignore`).
