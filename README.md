# Goms STS Claude Plugin V1.3.0 (source-only PDE project)

A **prototype** Eclipse plug-in for Spring Tools Suite (STS). It adds **Goms AI Tools → Claude Code Chat**, uses your existing installed Claude Code CLI, detects the selected Eclipse project (with a folder-picker override), sends questions asynchronously, and retains the session within the view.

## V1.3.0 changes - Build / Test automation (Maven)

A **Maven** row is added: `[ auto: mvnw, MAVEN_HOME or PATH ] [...] [Compile] [Test] [Fix with Claude]`.

| Action | What happens |
|---|---|
| **Compile** / **Test** | Runs `mvn test-compile` / `mvn test` for the project of the active editor file (else the root pom, else you pick from a list). Full log in the **Console view -> Goms Maven**; compile errors and failed tests appear in the **Problems view** (`[mvn]`, `[mvn test]`) and as a short report in the chat history. |
| **Fix with Claude** | Enabled after a failed build. Switches to *Edit with approval* and sends the build report to Claude. Claude edits (each change approved in the diff dialog) and calls `run_maven` to verify, up to 3 build attempts. |
| Claude-initiated builds | In edit mode Claude has one extra tool, `run_maven` (goal `compile`, `test-compile` or `test`, optional `test` filter such as `CalcTest#add`, optional `module`). Every build asks first: *Run* / *Run builds for this request* / *Deny*. |

Design and safety:
- **No shell for Claude.** Builds are run by the plug-in, not by Claude: only the three goals exist, the test filter and module are validated (`[A-Za-z0-9_.*#,+$-]`, module must be inside the root and contain `pom.xml`), so a build request cannot become an arbitrary command. Arguments: `-B -ntp -Dstyle.color=never -DtrimStackTrace=true [-Dtest=... -Dsurefire.failIfNoSpecifiedTests=false] <goal>`.
- Build permission is separate from file permission: *Approve all (this run)* for edits does not approve builds.
- Maven launcher: project `mvnw(.cmd)` (module folder or a parent up to the root) > the path in the Maven field > `MAVEN_HOME`/`M2_HOME` > `PATH`. `.cmd` launchers run through `cmd.exe /c`. If `JAVA_HOME` is not set, the JDK running STS is used (a note is printed).
- Output is read in the platform encoding (e.g. MS949 on Korean Windows), so localized javac messages are shown correctly.
- Parsed: javac errors/warnings with `symbol:`/`location:` details (deduplicated; `/D:/...` Windows paths handled), Surefire 2.x and 3.x per-test failures with the test line taken from the stack trace (incl. parameterized tests and nested classes), summary fallback, multi-module totals, other `[ERROR]`s such as dependency resolution failures.
- Timeout 15 minutes per build; **Cancel** stops the build and its forked JVMs.
- Files under `target/`, `bin/`, `build/` are ignored by the "changed without approval" warning, since builds write them.
- New dependency: `org.eclipse.ui.console`. New marker type `kr.co.goms.sts.claude.buildProblem` (not persisted).

### V1.3 acceptance test in the runtime workbench

Use a small Maven project with JUnit 5 (e.g. a `Calc` class and `CalcTest`), ideally with `mvnw`.

1. Open a source file of the project, click **Compile** -> Console shows the Maven log, history shows `[BUILD] BUILD SUCCESS ...`.
2. Introduce a compile error (e.g. misspell a variable) and save, click **Compile** -> `BUILD FAILURE · 1 compile error`, an `[mvn]` entry in the Problems view (double-click jumps to the line).
3. Click **Fix with Claude** -> mode switches to edit, Claude proposes a fix (approve it), then asks to run `mvn ...` (approve) -> `[BUILD by Claude] BUILD SUCCESS`, Problems entry gone.
4. Make a test fail (wrong expected value in `CalcTest`), click **Test** -> `tests N run, 1 failed`, `[mvn test] CalcTest#... failed: expected ...` in Problems at the assertion line.
5. In edit mode ask *"Run the tests and fix any failure"* -> Claude calls `run_maven` (approve), fixes, re-runs.
6. Deny a build request -> Claude reports that it could not verify; nothing runs.
7. Start **Test** on a slow build and press **Cancel** -> `Build cancelled`.
8. Workspace root with several Maven projects and no root pom, no editor open -> **Compile** asks which project to build.

## V1.2.1 changes - V1.1 Java Editor Context

Your question now carries what you are looking at in the editor, in both modes.

- A line **Include editor context: ...** under the Mode selector shows, live, what will be attached:
  `Hello.java · L10-14 (5 lines) · method Hello.main(String[])`, or `cursor L3 · class Hello` without a selection, plus `unsaved` / `outside workspace root` flags. Untick it to send the question alone.
- On **Send**, the plug-in reads the active (or last active) text editor and puts an `<ide_context>` block before your message:
  file path relative to the workspace root, Eclipse project, cursor line, the **enclosing Java element** (method with parameter types, class, field; via JDT), and the **selected code** in a code fence (max 20,000 characters, the rest is left to Claude to read).
  So "explain this", "refactor the selection" or "add a test for this method" work without typing file names.
- If the editor has unsaved changes the block says so, because Claude reads files from disk; in edit mode, save before asking for changes.
- **Ask Claude about Selection**: right-click in the Java editor (or any text editor) or press **Ctrl+Alt+K**. It opens Claude Code Chat with that selection *pinned* (`[pinned]` in the context line), so clicking elsewhere does not lose it; the pin is used by the next Send.
- The history shows `YOU [context: Hello.java · L10-14 ...]` instead of the whole block.

### V1.1 acceptance test in the runtime workbench

1. Open a Java file inside the selected workspace root and select a few lines in a method -> the context line shows file, line range and `method ...`.
2. Ask *"What does this code do?"* (Read-only mode) -> the answer is about the selected lines; the history shows `[context: ...]`.
3. Click inside another method without selecting -> the context line shows `cursor L.. · method <other>`; ask *"Summarize this method"* -> answer is about that method.
4. Select code, right-click -> **Ask Claude about Selection** (or Ctrl+Alt+K) -> the view opens with `[pinned]`; click into Package Explorer, then ask -> the pinned selection is still used.
5. Type in the editor without saving -> `unsaved` appears; untick *Include editor context* -> `(off)` and the next question is sent without context.
6. Edit mode: select a method and ask *"Add null checks to this method"* -> the approval dialog shows a change in that method.

## V1.2.0 changes - Code Edit + Human Approval

A **Mode** selector is added next to *Browse workspace folder*:

| Mode | What Claude can do |
|---|---|
| **Read-only (plan)** | Same as V1.0.2: `--permission-mode plan`, tools `Read,Glob,Grep`. |
| **Edit with approval** | Tools `Read,Glob,Grep,Edit,Write`. **Every** Edit/Write opens a diff dialog first; nothing is written unless you approve. |

How edit mode works (design validated in `poc/v1.2-approval`, see its README):

```
ClaudeChatView ──> claude -p ... --permission-prompt-tool mcp__goms_ide__approve
                        │ Edit/Write request
                        ▼
   McpApprovalServer (127.0.0.1, random port, bearer token) ─> ApprovalBroker
        auto-deny unsafe paths  /  ApprovalDialog (Eclipse Compare) ─> allow / deny + reason
```

- **ApprovalDialog** (modeless): left = current file, right = Claude's proposal. The right side is **editable**; your edits are sent back as `updatedInput`, so Claude writes your version.
  Buttons: *Approve*, *Approve file (this run)*, *Approve all (this run)*, *Reject* (the reason text goes back to Claude so it can revise). Closing the dialog = reject.
- **Auto-denied without asking**: paths outside the workspace root (including symlink escapes); anything under `.git`, `.claude`, `.settings`, `.metadata`, `.idea`, `.svn`; files > 2 MB; Edits whose `old_string` is missing or ambiguous; tools other than Edit/Write.
- **Allow rules cannot skip approval**: a `--settings` file with `permissions.ask: ["Edit","Write"]` is always passed; ask rules beat allow rules from project/user settings (PoC S6 showed a project allow rule bypassed the prompt tool; S11 showed this fixes it).
- **Safety net**: changes under the root that were never approved (e.g. written by a hook or managed policy) are reported as `[IDE WARNING]` after the run. Works for folders that are part of the Eclipse workspace.
- **Revert last change**: the exact bytes before each approved change are kept (last 50); the button restores them, or deletes a file Claude created. Claude writes files itself, so Eclipse Local History does not have these versions.
- Approved files are refreshed in Eclipse automatically; an editor with unsaved changes for the target file is flagged in the dialog.
- File encoding for previews comes from the Eclipse project/file settings (UTF-8 outside the workspace). CRLF files are matched and kept as CRLF.
- **Prompt is sent through stdin** (both modes) instead of as a command-line argument: double quotes, newlines and long prompts now survive on Windows.
- The approval server is a tiny built-in HTTP/1.1 server on plain sockets (no `com.sun.net.httpserver`, which OSGi bundles cannot rely on).
- New dependencies in `MANIFEST.MF`: `org.eclipse.compare`, `org.eclipse.core.filebuffers`, `org.eclipse.text`, `org.eclipse.jface.text` (all part of STS).

### V1.2 acceptance test in the runtime workbench

Use a scratch copy or a clean Git working tree.

1. Launch the Eclipse application, open **Claude Code Chat**, select the workspace folder, choose **Edit with approval**.
2. *"Add a line printing the current time to main in src/Hello.java and create src/Greeter.java"* -> two dialogs; approve both -> files change and Eclipse shows them without manual refresh.
3. Reject an Edit with a reason -> Claude sends a revised proposal.
4. Edit the right side before approving -> the file contains your version.
5. **Revert last change** -> previous content restored (or the new file deleted).
6. *Cancel* while a dialog is open -> dialog closes, nothing is written.
7. Ask for a file outside the root or in `.git` -> `[IDE] Auto-denied ...` in the history, no dialog.
8. Switch back to **Read-only (plan)** and ask a question with "double quotes" in it -> answer arrives (stdin prompt).

### CLI arguments (edit mode)

```
claude --print --output-format text --permission-mode default --tools Read,Glob,Grep,Edit,Write
  --mcp-config <temp>/mcp.json --strict-mcp-config --permission-prompt-tool mcp__goms_ide__approve
  --settings <temp>/force-ask.settings.json --append-system-prompt "<IDE approval notice>" --max-turns 30
  --session-id|--resume <uuid>          (prompt on stdin, env MCP_TOOL_TIMEOUT=1800000)
```

## V1.0.2 changes

- **Workspace root** accepts **any existing readable directory**. `pom.xml` / `build.gradle` / `build.gradle.kts` are no longer required.
- Supports a multi-project root such as `D:\06.BWG-AI\01.SR-Automation` with five child project directories, even before their build files are created.
- Renamed `Project root` UI to **Workspace root** and updated folder dialog / status / help wording.
- The **Use selection** button still uses a selected Eclipse project. To choose a parent directory not imported as an Eclipse project, click **Browse workspace folder** or type the parent path directly.
- Added `WorkspaceRootValidator` and automated smoke checks for an empty-build-file multi-project root, blank value, and nonexistent path.
- Retains V1.0.1 changes: no-selection dialog and `org.eclipse.ui.ide` dependency fix.

## Included / intentionally omitted

**Included**
- Eclipse `ViewPart` built with SWT, no React/server required.
- Workspace root detection from selected Java resource or active file editor, with folder picker override.
- Native Claude CLI executable location chooser.
- Asynchronous CLI execution and process cancellation.
- Session isolation by selected workspace root and explicit **New chat** reset.
- Read-only-first flags: `--permission-mode plan`, `--tools Read,Glob,Grep`, `--disallowedTools mcp__*`.
- Validates that the workspace path exists and is a readable directory, independent of build files.

**Not yet included**: token-by-token streaming, Markdown/HTML rendering, persistent UI sessions across STS restarts, Gradle builds, Eclipse update site. A Claude subscription or supported API billing is separate and still required.

## Prerequisites

1. STS based on Eclipse Platform and Java 17+ capable of running PDE plug-ins.
2. PDE available: `Help → Install New Software` / Eclipse Marketplace, install **Eclipse Plug-in Development Environment**, if absent.
3. Claude Code installed and logged in **outside STS**, per the [official setup](https://code.claude.com/docs/en/setup):

   ```powershell
   irm https://claude.ai/install.ps1 | iex
   claude --version
   claude auth status
   ```

4. Use native Windows `claude.exe` for reliable `ProcessBuilder` execution. The plug-in tries `%USERPROFILE%\.local\bin\claude.exe` first; if installed elsewhere use **Browse**. A `.cmd` shell wrapper is **not** supported by this V1.0 implementation.

## Import and run in STS

1. Extract the ZIP to a working directory (e.g. `C:\1004.GomsBook\03.Project\Goms-STS-Claude-Plugin`).
2. STS: `File → Import → General → Existing Projects into Workspace` and select the extracted folder.
3. If `org.eclipse.pde.PluginNature` or `org.eclipse.*` dependencies cannot resolve, verify PDE is installed and `Window → Preferences → Plug-in Development → Target Platform` points to your Eclipse/STS running target. Use `Project → Clean` afterward.
4. Open `MANIFEST.MF` then select `Overview → Launch an Eclipse application` (or `Run As → Eclipse Application`). This starts a **second Eclipse workbench** for testing. Importing a project is optional; the workspace directory can be selected directly.
5. In that workbench use `Window → Show View → Other → Goms AI Tools → Claude Code Chat`.
6. Click **Browse workspace folder**, choose `D:\06.BWG-AI\01.SR-Automation` (or type the path directly). No projects need to be imported into the runtime STS workspace and no Maven/Gradle files need to exist. **Use selection** remains available for choosing an imported Eclipse project.
7. Confirm Claude path, type `List the five child project folders in this workspace. Do not change files.`, and press **Send**.
8. When it responds, send a follow-up question in the same view; it resumes the unique Claude CLI session for that workspace root.

## Troubleshooting

- **`Could not start Claude CLI`**: select the real `claude.exe` via **Browse**; do not supply `claude.cmd`.
- **Authentication error / process exits**: run `claude` from regular PowerShell, complete login first; IDE does not conduct auth itself.
- **Wrong workspace**: click **Browse workspace folder** and choose the parent directory. **Use selection** picks the selected Eclipse project, not its parent directory.
- **CLI appears to run indefinitely**: press **Cancel** (forcibly terminates that process). Note the CLI itself can make background session records.
- **A new chat uses an old response**: press **New chat**; each workspace/session has a distinct UUID. Conversations aren't restored after restarting STS.
- **Source file changes despite plan mode**: check project-level Claude hooks and external processes. This is a *UI/permission limitation*, not an OS sandbox or a formal guarantee of no writes; test on a clean Git working tree.
- **Build fails with Java version**: V1.0.2 targets Java 17; ensure STS project JRE and PDE target support 17 or newer.

## Why this architecture

The plug-in is a thin UI shell. Authentication, context/tool management, inference, and actual session storage remain with installed Claude Code. It does **not** embed an undocumented chat API or make its own Anthropic HTTP calls.

### CLI arguments used

Initial:

```
claude --print --output-format text --permission-mode plan --tools Read,Glob,Grep --disallowedTools mcp__* --max-turns 12 --session-id <uuid>
```

The prompt is written to stdin (since V1.2). Follow-up replaces `--session-id` with `--resume`. Edit mode arguments: see *V1.2.0 changes*.

## Next iteration roadmap

- V1.1: editor context - done (in 1.2.1). `stream-json` progress moved to a later iteration.
- V1.2: done (this version). Next: `stream-json` progress, approval of a whole change set at once.
- V1.3: Maven build/test with Problems view and Claude fix loop - done (this version). Next candidates: Gradle, stream-json progress, whole change-set approval.

Official references: [Claude CLI options](https://code.claude.com/docs/en/cli-reference), [programmatic execution](https://code.claude.com/docs/en/headless), [Eclipse ViewPart](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/reference/api/org/eclipse/ui/part/ViewPart.html).

## Maintainer smoke-tests

### V1.2 approval core (no Eclipse, no Claude)

```bash
javac --release 17 -d out src/kr/co/goms/sts/claude/approval/*.java tests/ApprovalCoreSmokeTest.java
java -cp out ApprovalCoreSmokeTest
```

47 checks: path guard, CRLF preview, edit-to-`updatedInput` rewriting (incl. ~3,000 randomized round-trips), broker decisions/memory/cancel, MCP over HTTP.
The full plug-in source was also compiled against the STS 4.24 bundles (only the MANIFEST-visible ones) with no errors or warnings; the runtime workbench test above is still the acceptance test.

### V1.3 build core (parser, runner, locator, run_maven over MCP)

```bash
javac --release 17 -d out src/kr/co/goms/sts/claude/approval/*.java \
  src/kr/co/goms/sts/claude/build/{BuildProblem,TestFailure,BuildRequest,BuildResult,MavenOutputParser,MavenProjectLocator,MavenRunner,BuildFixPrompt}.java \
  tests/BuildCoreSmokeTest.java
java -cp out BuildCoreSmokeTest
```

56 checks; the runner / end-to-end parts use a fake `mvn` shell script (Linux/macOS/Git Bash). `BuildMarkers` and `BuildConsole` need Eclipse.

### V1.1 editor context block

```bash
javac --release 17 -encoding UTF-8 -d out src/kr/co/goms/sts/claude/context/EditorContext*.java tests/EditorContextFormatterSmokeTest.java
java -cp out EditorContextFormatterSmokeTest
```

(`EditorContextCapture.java` needs Eclipse; compile only the two files above.)

### CLI layer

A Unix-compatible shell test mock is bundled at `tests/fake-claude.sh` to validate command construction and cancellation without calling Anthropic or reading project content. Example on Linux/macOS/Git Bash with JDK 17+:

```bash
javac --release 17 -d out src/kr/co/goms/sts/claude/service/ClaudeCliService.java tests/ClaudeCliServiceSmokeTest.java
java -cp out ClaudeCliServiceSmokeTest "$PWD"
```

The CLI service smoke test has been executed successfully. **The Eclipse/PDE runtime itself has not been compiled or launched in the author's environment**, so import and launch in your actual STS remains the acceptance test. Avoid treating the ZIP as a release-ready signed plug-in.

### V1.0.2 standalone root validation smoke test

```bash
javac --release 17 -d out src/kr/co/goms/sts/claude/context/WorkspaceRootValidator.java tests/WorkspaceRootValidatorSmokeTest.java
java -cp out WorkspaceRootValidatorSmokeTest
```

For STS runtime use `D:\06.BWG-AI\01.SR-Automation` as **Workspace root** and select `D:\18.AI-CLI\ClaudeCode\claude.exe` as **Claude path**, if those are the actual paths on your PC. The CLI launches with that workspace as the process working directory. The tool is read-only-oriented, **not an OS-level sandbox**; do not use on sensitive content until you have checked Claude Code configuration, hooks, and allowed tools.
