# V1.2 PoC (1단계) — Permission Prompt Tool 검증

V1.2 "Code Edit + Human Approval"를 Eclipse에 넣기 전에, **Claude Code CLI가 모든 파일 변경 요청을 우리 서버에 먼저 묻고, 그 결정을 그대로 따르는지**를 Eclipse 없이 확인합니다.

```
run-poc.ps1 ─┬─> [Goms IDE Approval 창]  java GomsApprovalMcpServer.java   (127.0.0.1:랜덤포트, Bearer 토큰)
             │        ▲  tools/call approve {tool_name, input}  →  diff 표시 → a / d / e 선택
             └─> claude -p --permission-prompt-tool mcp__goms_ide__approve --tools Read,Glob,Grep,Edit,Write ...
```

나중에 플러그인에서는 콘솔 대신 Eclipse Compare 다이얼로그가 **같은 결정 규칙**(`allow + updatedInput` / `deny + message`)을 돌려주게 됩니다.

## 구성

| 파일 | 설명 |
|---|---|
| `src/GomsApprovalMcpServer.java` | 외부 라이브러리 없이 JDK 17만으로 동작하는 최소 MCP 서버(Streamable HTTP, JSON 응답). 도구는 `approve` 하나. 경로 검사, Edit/Write diff 미리보기, 콘솔 승인, JSONL 로그를 담당 |
| `run-poc.ps1` / `run-poc.cmd` | 서버를 별도 창으로 띄우고 → `claude -p` 실행 → 승인 로그와 `git diff` 리포트 → 서버 종료 |
| `smoke-test.ps1` | Claude 없이 프로토콜만 검증(로그인·토큰 불필요). 10개 항목 |

PDE 빌드(`build.properties`의 `src/`)와는 분리돼 있어서 플러그인 번들에는 들어가지 않습니다.

## 준비

- JDK 17 이상의 `java`. JRE가 아닌 JDK여야 합니다(소스를 바로 실행하는 single-file launch 사용). PATH에 없으면 `-Java "C:\...\jdk-17\bin\java.exe"`로 지정하세요.
- 로그인된 `claude.exe`. 기본 위치는 `%USERPROFILE%\.local\bin\claude.exe`이고, 다른 곳에 있으면 `-Claude D:\18.AI-CLI\ClaudeCode\claude.exe`처럼 지정합니다.
- (권장) `git`. 있으면 샘플 워크스페이스를 git으로 초기화해서 결과를 `git diff`로 보여줍니다.

## 실행

```powershell
cd D:\04.GomsBook-AI\Goms-STS-Claude-Plugin\poc\v1.2-approval

powershell -ExecutionPolicy Bypass -File .\smoke-test.ps1   # 1. 프로토콜 확인 (ALL PASSED 확인)
.\run-poc.cmd                                              # 2. 실제 Claude와 연동 (샘플 워크스페이스 자동 생성)

# 경로를 직접 지정할 때
.\run-poc.cmd -Java "C:\Program Files\Java\jdk-17\bin\java.exe" -Claude "D:\18.AI-CLI\ClaudeCode\claude.exe"
```

`run-poc.cmd`는 ExecutionPolicy를 우회해서 실행합니다. `.ps1`을 직접 실행하려면 `powershell -ExecutionPolicy Bypass -File .\run-poc.ps1`을 쓰세요.

**Goms IDE Approval** 창에 요청이 하나씩 표시됩니다.

| 키 | 동작 | Claude에 전달되는 값 |
|---|---|---|
| `a` | 이번 요청 승인 | `{"behavior":"allow","updatedInput":<원본>}` |
| `f` | 이 파일은 이번 실행 동안 계속 승인 | allow (기억) |
| `A` | 이번 실행의 모든 변경 승인 | allow (기억) |
| `d` | 거부하고 사유 입력 | `{"behavior":"deny","message":"<사유>"}` → Claude가 사유를 보고 수정안을 다시 제시 |
| `e` | 제안 내용을 메모장으로 고친 뒤 승인 | `updatedInput`에 고친 `content`(Write) 또는 `new_string`(Edit) |
| `j` | 원본 JSON 보기 | — |

자동으로 거부되는 요청(창에 `AUTO-DENY`로 표시):
- 워크스페이스 밖 경로(심볼릭 링크로 빠져나가는 경우 포함)
- `.git`, `.claude`, `.settings`, `.metadata`, `.idea` 아래 경로
- 2MB를 넘는 파일
- `old_string`이 없거나 여러 번 나오는 Edit
- MultiEdit, NotebookEdit

## 검증 시나리오 체크리스트

| # | 명령 | 기대 결과 | 확인 |
|---|---|---|---|
| S1 | `.\run-poc.cmd` → 모두 `a` | Hello.java 수정과 Greeter.java 생성이 diff로 표시되고, 승인한 것만 반영됨 | ☐ |
| S2 | `.\run-poc.cmd -ResetSample` → Edit에 `d` + "System.out 대신 java.util.logging 을 써줘" | Claude가 사유를 반영한 **새 요청**을 다시 보냄 | ☐ |
| S3 | `-ResetSample` → `e`로 메모장에서 수정 후 저장·종료 | **수정한 내용**이 파일에 반영됨 (`updatedInput` 동작) | ☐ |
| S4 | `.\run-poc.cmd -ResetSample -Mode deny` | `git status`가 깨끗함 = 거부하면 아무것도 쓰지 않음 | ☐ |
| S5 | `-Prompt "..\outside.txt 파일을 만들어줘"` | AUTO-DENY, 워크스페이스 밖에 파일이 생기지 않음 | ☐ |
| S6 | `.\run-poc.cmd -ResetSample -BypassTest` | **2026-10-17 결과: BYPASS DETECTED.** 핵심 위험 확인: 프로젝트 `.claude/settings.local.json`의 `allow: [Edit, Write]` 규칙이 승인을 건너뛰는지. 결과 메시지가 `BYPASS DETECTED`인지 `No bypass`인지 기록 | ☑ |
| S7 | S6이 BYPASS이면 `-BypassTest -SettingSources user` | **2026-10-17 결과: 승인 요청 2건이 다시 옴.** 프로젝트·로컬 설정을 빼면 승인 요청이 다시 오는지 → V1.2 기본 인자 결정 | ☑ |
| S11 | `.\run-poc.cmd -ResetSample -BypassTest -ForceAsk` | 프로젝트 allow 규칙이 있어도 `--settings`의 `ask: [Edit, Write]`가 우선해서 승인 요청이 오는지. 오면 프로젝트 설정을 유지한 채 우회를 막을 수 있음 | ☐ |
| S8 | 승인 창에서 5분 이상 기다린 뒤 `a` | 타임아웃 없이 계속 진행됨 (`MCP_TOOL_TIMEOUT=1800000`) | ☐ |
| S9 | `-OutputFormat stream-json` | `out\stream.jsonl`에 tool_use/tool_result 이벤트가 남음 → V1.2 스트림 파서 설계 자료 | ☐ |
| S10 | CRLF 파일 편집 후 `git diff` | 줄바꿈이 섞이지 않는지(Note의 `Line endings` 표시와 실제 결과 비교) | ☐ |

결과는 `out\`에 남습니다: `approvals.jsonl`(요청과 결정 기록), `claude-output.txt` 또는 `stream.jsonl`, `mcp.json`. S6~S10의 결과에 따라 V1.2 플러그인의 기본 CLI 인자와 안전망 범위가 정해집니다.

## 알려진 제약 (PoC 범위)

- 미리보기는 파일을 UTF-8로 읽습니다. 플러그인에서는 `IFile.getCharset()`으로 바꿀 예정입니다.
- diff는 줄바꿈 차이를 무시하고, 줄바꿈 변경은 `Note:` 줄에 따로 표시합니다.
- 콘솔 입력은 한 번에 한 요청만 처리합니다. 동시에 요청이 오면 순서대로 대기합니다.
- 사유를 한글로 입력할 때 JDK 17 콘솔 환경에 따라 글자가 깨질 수 있습니다. 문제가 되면 영어로 입력하세요. Eclipse UI에서는 해당하지 않습니다.
- Windows PowerShell 5.1은 큰따옴표(`"`)가 들어간 `-Prompt` 문자열을 제대로 넘기지 못할 수 있습니다. 프롬프트에 큰따옴표를 넣지 마세요.
