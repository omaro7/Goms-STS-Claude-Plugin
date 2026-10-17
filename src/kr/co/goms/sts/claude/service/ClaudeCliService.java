package kr.co.goms.sts.claude.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Runs the installed Claude Code CLI. Does not replace its auth or agent engine. */
public final class ClaudeCliService {

    public interface Listener {
        void onSuccess(String response);
        void onFailure(String error);
        void onCancelled();
    }

    /**
     * Edit-with-approval mode (V1.2): Edit/Write are enabled but every request goes to the IDE's
     * permission prompt tool. {@code settingsFile} forces "ask" for Edit/Write so allow rules in project
     * or user settings cannot skip the IDE (verified in poc/v1.2-approval S6/S11).
     *
     * @param mcpConfig       --mcp-config file pointing at the IDE approval server
     * @param settingsFile    --settings file with permissions.ask = [Edit, Write]
     * @param permissionTool  --permission-prompt-tool value
     */
    public record EditMode(Path mcpConfig, Path settingsFile, String permissionTool) {
        public EditMode {
            Objects.requireNonNull(mcpConfig, "mcpConfig");
            Objects.requireNonNull(settingsFile, "settingsFile");
            Objects.requireNonNull(permissionTool, "permissionTool");
        }
    }

    static final String EDIT_SYSTEM_PROMPT =
            "You are running inside the Spring Tools Suite IDE through the Goms plugin. "
            + "Every Edit or Write is shown to the developer as a diff and must be approved in the IDE. "
            + "If a change is rejected, read the rejection message, then propose a revised change or ask a question. "
            + "Keep each edit focused on one concern. Shell commands are not available in this mode; "
            + "to verify changes use the run_maven tool (goal compile, test-compile or test, optional test filter and module), "
            + "which the developer also approves. Fix what it reports before finishing.";

    /** 30 minutes: the developer may take a while to review a diff (PoC S8). */
    static final String MCP_TOOL_TIMEOUT_MS = "1800000";

    private volatile Process activeProcess;
    private volatile Thread activeThread;
    private volatile boolean cancelled;

    /** Read-only (plan mode) question, as in V1.0.2. */
    public void ask(String executable, Path projectRoot, String prompt, UUID sessionId,
            boolean resumeSession, Listener listener) {
        ask(executable, projectRoot, prompt, sessionId, resumeSession, null, listener);
    }

    /** @param editMode null for read-only plan mode, otherwise edit-with-approval mode */
    public synchronized void ask(String executable, Path projectRoot, String prompt, UUID sessionId,
            boolean resumeSession, EditMode editMode, Listener listener) {
        Objects.requireNonNull(listener, "listener");
        Objects.requireNonNull(projectRoot, "projectRoot");
        Objects.requireNonNull(sessionId, "sessionId");
        if (activeThread != null && activeThread.isAlive()) throw new IllegalStateException("Claude is already running");
        if (!Files.isDirectory(projectRoot)) throw new IllegalArgumentException("Project directory not found: " + projectRoot);
        if (executable == null || executable.isBlank()) throw new IllegalArgumentException("Claude executable is required");
        if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("Please enter a prompt");

        cancelled = false;
        List<String> command = buildCommand(executable.strip(), sessionId, resumeSession, editMode);
        Path dir = projectRoot.toAbsolutePath().normalize();
        Thread thread = new Thread(() -> runCommand(command, dir, prompt, editMode != null, listener), "goms-claude-cli");
        thread.setDaemon(true);
        activeThread = thread;
        thread.start();
    }

    public boolean isRunning() {
        Thread t = activeThread;
        return t != null && t.isAlive();
    }

    /** Terminates only the subprocess started by this service. */
    public void cancel() {
        cancelled = true;
        Process process = activeProcess;
        if (process != null) killProcessTree(process);
    }

    /** The prompt is NOT an argument: it is written to stdin (no Windows quoting issues, no length limit). */
    static List<String> buildCommand(String executable, UUID sessionId, boolean resumeSession, EditMode editMode) {
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.add("--print");
        command.add("--output-format");
        command.add("text");
        if (editMode == null) {
            command.add("--permission-mode");
            command.add("plan");
            command.add("--tools");
            command.add("Read,Glob,Grep");
            command.add("--disallowedTools");
            command.add("mcp__*");
            command.add("--max-turns");
            command.add("12");
        } else {
            command.add("--permission-mode");
            command.add("default");
            command.add("--tools");
            command.add("Read,Glob,Grep,Edit,Write");
            command.add("--mcp-config");
            command.add(editMode.mcpConfig().toString());
            command.add("--strict-mcp-config");
            command.add("--permission-prompt-tool");
            command.add(editMode.permissionTool());
            command.add("--settings");
            command.add(editMode.settingsFile().toString());
            command.add("--append-system-prompt");
            command.add(EDIT_SYSTEM_PROMPT);
            command.add("--max-turns");
            command.add("30");
        }
        command.add(resumeSession ? "--resume" : "--session-id");
        command.add(sessionId.toString());
        return command;
    }

    private void runCommand(List<String> command, Path projectRoot, String prompt, boolean editMode, Listener listener) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(projectRoot.toFile());
            builder.redirectErrorStream(true);
            if (editMode) builder.environment().put("MCP_TOOL_TIMEOUT", MCP_TOOL_TIMEOUT_MS);
            Process process = builder.start();
            activeProcess = process;
            if (cancelled) killProcessTree(process);

            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(prompt.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                if (!cancelled) throw e;
            }

            StringBuilder output = new StringBuilder();
            boolean truncated = false;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() < 200_000) output.append(line).append(System.lineSeparator());
                    else truncated = true;
                }
            }
            int exitCode = process.waitFor();
            if (cancelled) listener.onCancelled();
            else if (exitCode == 0) listener.onSuccess(output + (truncated ? "\n[Output truncated at 200k characters]" : ""));
            else listener.onFailure("Claude exited with code " + exitCode + ".\n" + output);
        } catch (IOException e) {
            if (cancelled) listener.onCancelled();
            else listener.onFailure("Could not start Claude CLI. Check executable path and login.\n" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Process process = activeProcess;
            if (process != null) killProcessTree(process);
            listener.onCancelled();
        } finally {
            activeProcess = null;
            activeThread = null;
        }
    }

    private static void killProcessTree(Process process) {
        try {
            List<ProcessHandle> children = process.descendants().toList();
            for (int i = children.size() - 1; i >= 0; i--) children.get(i).destroyForcibly();
        } catch (SecurityException ignored) { /* process itself is still terminated below */ }
        process.destroyForcibly();
    }

    public static String defaultExecutable() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            Path nativeBinary = Path.of(System.getProperty("user.home"), ".local", "bin", "claude.exe");
            if (Files.isRegularFile(nativeBinary)) return nativeBinary.toString();
            return "claude.exe";
        }
        return "claude";
    }
}
