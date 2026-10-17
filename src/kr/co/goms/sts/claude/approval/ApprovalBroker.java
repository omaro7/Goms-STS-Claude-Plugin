package kr.co.goms.sts.claude.approval;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Decides Claude Code permission requests: auto-denies unsafe ones, asks the user for the rest (one at a time),
 * remembers "allow file / allow all" for the current run and snapshots every approved file for revert.
 * Independent of Eclipse; the UI is plugged in through {@link Ui}.
 */
public final class ApprovalBroker {

    /** Shows a request to the user. The future must always complete (closing the dialog = deny). */
    public interface Ui {
        CompletableFuture<ApprovalDecision> requestApproval(ApprovalRequest request);

        /**
         * V1.3: asks whether a non-file tool (the Maven build tool) may run.
         * ALLOW = once, ALLOW_FILE/ALLOW_ALL = for the rest of this run, DENY = no. Default: deny.
         */
        default CompletableFuture<ApprovalDecision> requestToolApproval(String title, String description) {
            return CompletableFuture.completedFuture(ApprovalDecision.deny("This IDE does not allow " + title + "."));
        }
    }

    /** Progress notifications for the chat history. Called on HTTP worker threads. */
    public interface Listener {
        void onEvent(String message);
        void onApproved(ChangeRecord record);
    }

    private static final int HISTORY_LIMIT = 50;

    private final Ui ui;
    private final Listener listener;
    private final Function<Path, Charset> charsetResolver;
    private final ReentrantLock serial = new ReentrantLock(true);
    private final List<CompletableFuture<ApprovalDecision>> pending = new CopyOnWriteArrayList<>();
    private final Deque<ChangeRecord> history = new ArrayDeque<>();
    private final Set<String> allowedFiles = new HashSet<>();
    private final Set<String> approvedThisRun = new HashSet<>();
    private volatile Path root;
    private volatile boolean runActive;
    private boolean allowAll;
    private boolean allowBuilds;

    public ApprovalBroker(Ui ui, Listener listener, Function<Path, Charset> charsetResolver) {
        this.ui = ui;
        this.listener = listener;
        this.charsetResolver = charsetResolver != null ? charsetResolver : p -> StandardCharsets.UTF_8;
    }

    /** Starts a Claude run: sets the root and clears "allow file / allow all" memory. */
    public synchronized void beginRun(Path workspaceRoot) throws IOException {
        root = workspaceRoot.toRealPath();
        allowedFiles.clear();
        approvedThisRun.clear();
        allowAll = false;
        allowBuilds = false;
        runActive = true;
    }

    /** Ends the run; any request still waiting is denied. */
    public void endRun() {
        runActive = false;
        cancelPending("The Claude run ended.");
    }

    /** Denies every request still waiting for the user (Cancel button, view closed). */
    public void cancelPending(String reason) {
        for (CompletableFuture<ApprovalDecision> f : pending) f.complete(ApprovalDecision.deny(reason));
    }

    /** Keys (see {@link #key(Path)}) of files approved during the current run. */
    public synchronized Set<String> approvedKeysThisRun() {
        return Set.copyOf(approvedThisRun);
    }

    public synchronized ChangeRecord lastChange() {
        return history.peekLast();
    }

    public synchronized ChangeRecord removeLastChange() {
        return history.pollLast();
    }

    /** Normalized comparison key for a path (case-insensitive on Windows). */
    public static String key(Path p) {
        String s = p.toAbsolutePath().normalize().toString();
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? s.toLowerCase(Locale.ROOT) : s;
    }

    /**
     * Handles one permission-prompt-tool call.
     *
     * @param args {tool_name, input, tool_use_id}
     * @return {"behavior":"allow","updatedInput":{...}} or {"behavior":"deny","message":"..."}
     */
    public Map<String, Object> decide(Map<String, Object> args) {
        String toolName = String.valueOf(args.get("tool_name"));
        Map<String, Object> input = Json.asMap(args.get("input"));
        Object toolUseId = args.get("tool_use_id");
        Path currentRoot = root;
        if (currentRoot == null || !runActive) return deny("The IDE is not expecting changes right now.");
        if (McpApprovalServer.BUILD_TOOL.equals(toolName)) return decideBuild(input);

        ApprovalRequest request;
        try {
            request = EditPreview.build(currentRoot, toolName, input, toolUseId == null ? null : String.valueOf(toolUseId),
                    charsetFor(currentRoot, input));
        } catch (ApprovalRejection r) {
            listener.onEvent("Auto-denied " + toolName + ": " + r.getMessage());
            return deny(r.getMessage());
        }

        synchronized (this) {
            if (allowAll || allowedFiles.contains(key(request.file()))) {
                return approve(request, input, "Allowed (remembered) " + toolName + " " + request.displayPath());
            }
        }

        serial.lock();                       // one dialog at a time, in request order
        try {
            synchronized (this) {           // a previous dialog may have chosen "allow file/all"
                if (allowAll || allowedFiles.contains(key(request.file()))) {
                    return approve(request, input, "Allowed (remembered) " + toolName + " " + request.displayPath());
                }
            }
            CompletableFuture<ApprovalDecision> future;
            try {
                future = ui.requestApproval(request);
            } catch (RuntimeException e) {
                return deny("The IDE could not show the approval dialog: " + e.getMessage());
            }
            pending.add(future);
            ApprovalDecision decision;
            try {
                decision = future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                decision = ApprovalDecision.deny("Approval was interrupted.");
            } catch (ExecutionException e) {
                decision = ApprovalDecision.deny("Approval failed: " + e.getCause());
            } finally {
                pending.remove(future);
            }

            if (!decision.allowed()) {
                listener.onEvent("Rejected " + toolName + " " + request.displayPath() + ": " + decision.feedback());
                return deny(decision.feedback());
            }
            Map<String, Object> updated;
            try {
                updated = EditInputRewriter.rewrite(request, decision.editedAfter());
            } catch (ApprovalRejection r) {
                listener.onEvent("Could not apply your edit to " + request.displayPath() + ": " + r.getMessage());
                return deny("The user edited the proposal but it could not be applied: " + r.getMessage()
                        + " Please propose the change again.");
            }
            synchronized (this) {
                if (decision.kind() == ApprovalDecision.Kind.ALLOW_FILE) allowedFiles.add(key(request.file()));
                if (decision.kind() == ApprovalDecision.Kind.ALLOW_ALL) allowAll = true;
            }
            boolean edited = decision.editedAfter() != null && !decision.editedAfter().equals(request.after());
            return approve(request, updated, (edited ? "Approved with your edits: " : "Approved: ") + toolName + " " + request.displayPath());
        } finally {
            serial.unlock();
        }
    }

    /** V1.3: permission for the Maven build tool (separate from "allow all edits"). */
    private Map<String, Object> decideBuild(Map<String, Object> input) {
        String description = describeBuild(input);
        synchronized (this) {
            if (allowBuilds) {
                listener.onEvent("Build allowed (remembered): " + description);
                return allowInput(input);
            }
        }
        serial.lock();
        try {
            synchronized (this) {
                if (allowBuilds) {
                    listener.onEvent("Build allowed (remembered): " + description);
                    return allowInput(input);
                }
            }
            CompletableFuture<ApprovalDecision> future;
            try {
                future = ui.requestToolApproval("a Maven build", description);
            } catch (RuntimeException e) {
                return deny("The IDE could not ask about the build: " + e.getMessage());
            }
            pending.add(future);
            ApprovalDecision decision;
            try {
                decision = future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                decision = ApprovalDecision.deny("Approval was interrupted.");
            } catch (ExecutionException e) {
                decision = ApprovalDecision.deny("Approval failed: " + e.getCause());
            } finally {
                pending.remove(future);
            }
            if (!decision.allowed()) {
                listener.onEvent("Build rejected: " + description);
                return deny(decision.feedback());
            }
            if (decision.kind() != ApprovalDecision.Kind.ALLOW) synchronized (this) { allowBuilds = true; }
            listener.onEvent("Build approved: " + description);
            return allowInput(input);
        } finally {
            serial.unlock();
        }
    }

    /** "mvn test -Dtest=CalcTest (module demo)" from raw tool input, for display only. */
    static String describeBuild(Map<String, Object> input) {
        String goal = clean(input.get("goal"));
        String test = clean(input.get("test"));
        String module = clean(input.get("module"));
        return "mvn " + (goal.isEmpty() ? "?" : goal) + (test.isEmpty() ? "" : " -Dtest=" + test)
                + (module.isEmpty() ? "" : "  (module " + module + ")");
    }

    private static String clean(Object o) {
        if (o == null) return "";
        String s = String.valueOf(o).replaceAll("[\\p{Cntrl}]", " ").strip();
        return s.length() > 120 ? s.substring(0, 120) + "..." : s;
    }

    private static Map<String, Object> allowInput(Map<String, Object> input) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("behavior", "allow");
        m.put("updatedInput", input);
        return m;
    }

    private Charset charsetFor(Path currentRoot, Map<String, Object> input) {
        try {
            return charsetResolver.apply(PathGuard.resolve(currentRoot, input.get("file_path")));
        } catch (ApprovalRejection | RuntimeException e) {
            return StandardCharsets.UTF_8;   // the path is re-validated (and rejected) in EditPreview
        }
    }

    private Map<String, Object> approve(ApprovalRequest request, Map<String, Object> updatedInput, String message) {
        ChangeRecord record = snapshot(request);
        synchronized (this) {
            approvedThisRun.add(key(request.file()));
            history.addLast(record);
            while (history.size() > HISTORY_LIMIT) history.pollFirst();
        }
        listener.onEvent(message);
        listener.onApproved(record);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("behavior", "allow");
        m.put("updatedInput", updatedInput);
        return m;
    }

    private static ChangeRecord snapshot(ApprovalRequest request) {
        byte[] bytes = new byte[0];
        boolean existed = Files.exists(request.file());
        if (existed) {
            try {
                bytes = Files.readAllBytes(request.file());
            } catch (IOException e) {
                bytes = null;     // unknown content: revert is not offered for this record
            }
        }
        return new ChangeRecord(request.file(), request.displayPath(), request.toolName(), existed, bytes, Instant.now());
    }

    static Map<String, Object> deny(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("behavior", "deny");
        m.put("message", message);
        return m;
    }
}
