import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Goms STS Claude Plugin - V1.2 PoC (step 1).
 *
 * A dependency-free, minimal MCP server (Streamable HTTP, JSON responses only) that exposes ONE tool,
 * {@code approve}, intended for {@code claude -p --permission-prompt-tool mcp__goms_ide__approve}.
 * Every Edit/Write permission request from Claude Code arrives here; the operator decides in this console
 * (allow / deny with feedback / edit then allow). The same decision contract will later be driven by the
 * Eclipse diff dialog.
 *
 * Run (JDK 17+ single-file launch):
 *   java GomsApprovalMcpServer.java --root D:\ws --config out\mcp.json --log out\approvals.jsonl [--port 0] [--auto ask|allow|deny]
 */
public final class GomsApprovalMcpServer {

    static final String SERVER_NAME = "goms_ide";
    static final String TOOL_NAME = "approve";
    static final String VERSION = "0.1.0-poc";
    static final Set<String> PROTECTED_DIRS = Set.of(".git", ".claude", ".settings", ".metadata", ".idea");
    static final long MAX_PREVIEW_BYTES = 2_000_000;

    private final Path root;
    private final String token;
    private final String autoMode;
    private final Path logFile;
    private final ReentrantLock consoleLock = new ReentrantLock(true);
    private final PrintStream out = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, StandardCharsets.UTF_8);
    private final BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    private final Set<String> allowAllPaths = new HashSet<>();
    private boolean allowAllEdits;

    GomsApprovalMcpServer(Path root, String token, String autoMode, Path logFile) {
        this.root = root;
        this.token = token;
        this.autoMode = autoMode;
        this.logFile = logFile;
    }

    // ------------------------------------------------------------------ main

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parseArgs(args);
        String rootArg = opts.get("root");
        if (rootArg == null) { System.err.println("--root <workspace folder> is required"); System.exit(2); }
        Path root = Path.of(rootArg).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) { System.err.println("Workspace root not found: " + root); System.exit(2); }
        root = root.toRealPath();

        int port = Integer.parseInt(opts.getOrDefault("port", "0"));
        String token = opts.getOrDefault("token", randomToken());
        String auto = opts.getOrDefault("auto", "ask").toLowerCase(Locale.ROOT);
        if (!Set.of("ask", "allow", "deny").contains(auto)) { System.err.println("--auto must be ask|allow|deny"); System.exit(2); }
        Path log = opts.containsKey("log") ? Path.of(opts.get("log")).toAbsolutePath() : null;
        if (log != null && log.getParent() != null) Files.createDirectories(log.getParent());

        GomsApprovalMcpServer app = new GomsApprovalMcpServer(root, token, auto, log);
        HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        http.createContext("/mcp", app::handle);
        http.setExecutor(Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "mcp-http"); t.setDaemon(true); return t; }));
        http.start();
        int actualPort = http.getAddress().getPort();
        String url = "http://127.0.0.1:" + actualPort + "/mcp";

        if (opts.containsKey("config")) writeMcpConfig(Path.of(opts.get("config")).toAbsolutePath(), url, token);

        app.out.println("==============================================================");
        app.out.println(" Goms IDE approval MCP server " + VERSION);
        app.out.println(" URL        : " + url);
        app.out.println(" Workspace  : " + root);
        app.out.println(" Mode       : " + auto);
        app.out.println(" Tool       : mcp__" + SERVER_NAME + "__" + TOOL_NAME);
        if (log != null) app.out.println(" Log        : " + log);
        app.out.println(" Waiting for Claude Code permission requests... (Ctrl+C to stop)");
        app.out.println("==============================================================");
        app.log(Map.of("event", "started", "url", url, "root", root.toString(), "mode", auto));
        Thread.currentThread().join();
    }

    static Map<String, String> parseArgs(String[] args) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) throw new IllegalArgumentException("Unexpected argument: " + args[i]);
            String key = args[i].substring(2);
            String value = (i + 1 < args.length && !args[i + 1].startsWith("--")) ? args[++i] : "true";
            m.put(key, value);
        }
        return m;
    }

    static String randomToken() {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static void writeMcpConfig(Path file, String url, String token) throws IOException {
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("type", "http");
        server.put("url", url);
        server.put("headers", Map.of("Authorization", "Bearer " + token));
        String json = Json.write(Map.of("mcpServers", Map.of(SERVER_NAME, server)));
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, json, StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    // ------------------------------------------------------------------ HTTP / JSON-RPC

    void handle(HttpExchange ex) throws IOException {
        try {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            if (auth == null || !constantTimeEquals(auth, "Bearer " + token)) {
                send(ex, 401, "application/json", "{\"error\":\"unauthorized\"}");
                return;
            }
            switch (ex.getRequestMethod()) {
                case "POST" -> handlePost(ex);
                case "DELETE" -> send(ex, 200, "text/plain", "");
                default -> { ex.getResponseHeaders().add("Allow", "POST, DELETE"); send(ex, 405, "text/plain", ""); }
            }
        } catch (Exception e) {
            log(Map.of("event", "http_error", "error", String.valueOf(e)));
            try { send(ex, 500, "application/json", Json.write(rpcError(null, -32603, "Internal error: " + e))); } catch (IOException ignored) { }
        } finally {
            ex.close();
        }
    }

    private void handlePost(HttpExchange ex) throws IOException {
        String body;
        try (InputStream in = ex.getRequestBody()) { body = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
        Object parsed;
        try { parsed = Json.parse(body); }
        catch (RuntimeException e) { send(ex, 400, "application/json", Json.write(rpcError(null, -32700, "Parse error"))); return; }

        List<Object> responses = new ArrayList<>();
        boolean batch = parsed instanceof List;
        List<?> messages = batch ? (List<?>) parsed : List.of(parsed);
        for (Object msg : messages) {
            if (!(msg instanceof Map<?, ?> m)) { responses.add(rpcError(null, -32600, "Invalid Request")); continue; }
            Object id = m.get("id");
            Object method = m.get("method");
            if (method == null) continue;                 // a response from client: ignore
            if (!m.containsKey("id")) continue;           // notification (e.g. notifications/initialized)
            responses.add(dispatch(String.valueOf(method), id, asMap(m.get("params")), ex));
        }
        if (responses.isEmpty()) { send(ex, 202, "text/plain", ""); return; }
        send(ex, 200, "application/json", Json.write(batch ? responses : responses.get(0)));
    }

    private Map<String, Object> dispatch(String method, Object id, Map<String, Object> params, HttpExchange ex) {
        switch (method) {
            case "initialize": {
                Object requested = params.get("protocolVersion");
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("protocolVersion", requested != null ? requested : "2025-06-18");
                result.put("capabilities", Map.of("tools", Map.of("listChanged", false)));
                result.put("serverInfo", Map.of("name", "goms-ide-approval", "version", VERSION));
                result.put("instructions", "Permission broker for the Goms STS plugin. Not meant to be called directly by the model.");
                ex.getResponseHeaders().add("Mcp-Session-Id", UUID.randomUUID().toString());
                log(Map.of("event", "initialize", "clientInfo", String.valueOf(params.get("clientInfo")), "protocolVersion", String.valueOf(requested)));
                return rpcResult(id, result);
            }
            case "ping":
                return rpcResult(id, Map.of());
            case "tools/list": {
                Map<String, Object> schema = new LinkedHashMap<>();
                schema.put("type", "object");
                schema.put("properties", Map.of(
                        "tool_name", Map.of("type", "string", "description", "Tool Claude Code wants to run"),
                        "input", Map.of("type", "object", "description", "Tool input"),
                        "tool_use_id", Map.of("type", "string")));
                schema.put("required", List.of("tool_name", "input"));
                Map<String, Object> tool = new LinkedHashMap<>();
                tool.put("name", TOOL_NAME);
                tool.put("description", "Asks the IDE user to approve or deny a Claude Code tool call (permission prompt tool).");
                tool.put("inputSchema", schema);
                return rpcResult(id, Map.of("tools", List.of(tool)));
            }
            case "tools/call": {
                String name = String.valueOf(params.get("name"));
                if (!TOOL_NAME.equals(name)) return rpcError(id, -32602, "Unknown tool: " + name);
                Map<String, Object> decision;
                try {
                    decision = decide(asMap(params.get("arguments")));
                } catch (Exception e) {                   // fail closed
                    decision = deny("IDE approval failed: " + e.getMessage());
                    log(Map.of("event", "decide_error", "error", String.valueOf(e)));
                }
                Map<String, Object> text = Map.of("type", "text", "text", Json.write(decision));
                return rpcResult(id, Map.of("content", List.of(text)));
            }
            default:
                return rpcError(id, -32601, "Method not found: " + method);
        }
    }

    // ------------------------------------------------------------------ approval logic

    Map<String, Object> decide(Map<String, Object> args) throws IOException {
        String toolName = String.valueOf(args.get("tool_name"));
        Map<String, Object> input = asMap(args.get("input"));
        String toolUseId = String.valueOf(args.get("tool_use_id"));

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("event", "permission_request");
        record.put("tool", toolName);
        record.put("tool_use_id", toolUseId);
        record.put("input_keys", String.valueOf(input.keySet()));

        Preview preview;
        try {
            preview = Preview.build(this, toolName, input);
        } catch (Rejection r) {
            record.put("decision", "auto_deny");
            record.put("reason", r.getMessage());
            log(record);
            announceAutoDecision(toolName, input, "AUTO-DENY: " + r.getMessage());
            return deny(r.getMessage());
        }
        record.put("path", preview.displayPath);

        if ("deny".equals(autoMode)) { record.put("decision", "auto_deny_mode"); log(record); return deny("The IDE user did not approve this change."); }
        if ("allow".equals(autoMode)) { record.put("decision", "auto_allow_mode"); log(record); announceAutoDecision(toolName, input, "AUTO-ALLOW " + preview.displayPath); return allow(input); }
        synchronized (this) {
            if (allowAllEdits || allowAllPaths.contains(preview.displayPath)) {
                record.put("decision", "allow_remembered"); log(record);
                announceAutoDecision(toolName, input, "ALLOW (remembered) " + preview.displayPath);
                return allow(input);
            }
        }

        consoleLock.lock();
        try {
            Map<String, Object> result = askOperator(toolName, input, preview, record);
            log(record);
            return result;
        } finally {
            consoleLock.unlock();
        }
    }

    private Map<String, Object> askOperator(String toolName, Map<String, Object> input, Preview preview, Map<String, Object> record) throws IOException {
        out.println();
        out.println("##############################################################");
        out.println(" PERMISSION REQUEST  " + OffsetDateTime.now().withNano(0));
        out.println(" Tool : " + toolName);
        out.println(" File : " + preview.displayPath + (preview.isNewFile ? "   [NEW FILE]" : ""));
        if (preview.note != null) out.println(" Note : " + preview.note);
        out.println("--------------------------------------------------------------");
        out.println(preview.diff);
        out.println("--------------------------------------------------------------");
        while (true) {
            out.println(" [a] Allow   [f] Allow all for this file   [A] Allow all for this run");
            out.println(" [d] Deny with feedback   [e] Edit proposed text, then allow   [j] Show raw JSON");
            out.print(" > ");
            out.flush();
            String choice = readLine();
            if (choice == null) { record.put("decision", "deny_no_console"); return deny("No operator console available."); }
            switch (choice.strip()) {
                case "a", "y" -> { record.put("decision", "allow"); return allow(input); }
                case "f" -> { synchronized (this) { allowAllPaths.add(preview.displayPath); } record.put("decision", "allow_file"); return allow(input); }
                case "A" -> { synchronized (this) { allowAllEdits = true; } record.put("decision", "allow_all"); return allow(input); }
                case "d", "n" -> {
                    out.print(" Feedback for Claude (Enter = default): ");
                    out.flush();
                    String msg = readLine();
                    if (msg == null || msg.isBlank()) msg = "The user rejected this change in the IDE.";
                    record.put("decision", "deny");
                    record.put("feedback", msg);
                    return deny(msg);
                }
                case "e" -> {
                    Map<String, Object> updated = editProposal(toolName, input);
                    if (updated == null) { out.println(" (edit cancelled)"); continue; }
                    record.put("decision", "allow_edited");
                    return allow(updated);
                }
                case "j" -> out.println(Json.write(input));
                default -> out.println(" Unknown choice: " + choice);
            }
        }
    }

    /** Opens the proposed text in an editor; returns updatedInput or null if unchanged/cancelled. */
    private Map<String, Object> editProposal(String toolName, Map<String, Object> input) throws IOException {
        String field = switch (toolName) {
            case "Write" -> "content";
            case "Edit" -> "new_string";
            default -> null;
        };
        if (field == null) { out.println(" Editing is supported for Edit/Write only."); return null; }
        String original = String.valueOf(input.get(field));
        String fileName = Path.of(String.valueOf(input.get("file_path"))).getFileName().toString();
        Path tmp = Files.createTempFile("goms-approve-", "-" + fileName);
        try {
            Files.writeString(tmp, original, StandardCharsets.UTF_8);
            List<String> cmd = editorCommand(tmp);
            out.println(" Opening editor: " + String.join(" ", cmd) + "  (save and close to continue)");
            Process p = new ProcessBuilder(cmd).inheritIO().start();
            p.waitFor();
            String edited = Files.readString(tmp, StandardCharsets.UTF_8);
            if (edited.startsWith("\uFEFF")) edited = edited.substring(1);   // Notepad may add a BOM
            if (edited.equals(original)) { out.println(" No change made."); return null; }
            Map<String, Object> updated = new LinkedHashMap<>(input);
            updated.put(field, edited);
            out.println(" Edited " + field + " will be sent as updatedInput.");
            return updated;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static List<String> editorCommand(Path file) {
        String editor = System.getenv("GOMS_EDITOR");
        if (editor == null || editor.isBlank()) {
            editor = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "notepad.exe"
                    : System.getenv().getOrDefault("EDITOR", "vi");
        }
        List<String> cmd = new ArrayList<>(List.of(editor.split(" ")));
        cmd.add(file.toString());
        return cmd;
    }

    private String readLine() throws IOException {
        Console console = System.console();
        if (console != null) return console.readLine();
        return stdin.readLine();
    }

    private void announceAutoDecision(String toolName, Map<String, Object> input, String message) {
        consoleLock.lock();
        try { out.println("[" + OffsetDateTime.now().withNano(0) + "] " + toolName + " -> " + message); }
        finally { consoleLock.unlock(); }
    }

    static Map<String, Object> allow(Map<String, Object> updatedInput) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("behavior", "allow");
        m.put("updatedInput", updatedInput);
        return m;
    }

    static Map<String, Object> deny(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("behavior", "deny");
        m.put("message", message);
        return m;
    }

    // ------------------------------------------------------------------ path guard + preview

    static final class Rejection extends RuntimeException {
        private static final long serialVersionUID = 1L;
        Rejection(String message) { super(message); }
    }

    /** Resolves a tool path inside the workspace root or throws Rejection. */
    Path guardPath(Object rawPath) throws IOException {
        if (rawPath == null || String.valueOf(rawPath).isBlank()) throw new Rejection("file_path is missing.");
        Path p;
        try { p = Path.of(String.valueOf(rawPath)); }
        catch (InvalidPathException e) { throw new Rejection("Invalid file_path: " + rawPath); }
        if (!p.isAbsolute()) p = root.resolve(p);
        p = p.normalize();

        // Resolve symlinks on the deepest existing ancestor so a link cannot escape the root.
        Path existing = p;
        while (existing != null && !Files.exists(existing)) existing = existing.getParent();
        if (existing == null) throw new Rejection("Path has no existing ancestor: " + p);
        Path realAncestor = existing.toRealPath();
        Path real = realAncestor.resolve(existing.relativize(p)).normalize();
        if (!real.startsWith(root)) throw new Rejection("Path is outside the workspace root (" + root + "): " + p);

        for (Path part : root.relativize(real)) {
            if (PROTECTED_DIRS.contains(part.toString().toLowerCase(Locale.ROOT)))
                throw new Rejection("Writes under '" + part + "' are blocked by the IDE: " + root.relativize(real));
        }
        if (Files.isDirectory(real)) throw new Rejection("Path is a directory: " + root.relativize(real));
        if (Files.exists(real) && Files.size(real) > MAX_PREVIEW_BYTES) throw new Rejection("File is too large to review in the IDE (> 2 MB).");
        return real;
    }

    static final class Preview {
        String displayPath;
        String diff;
        String note;
        boolean isNewFile;

        static Preview build(GomsApprovalMcpServer s, String toolName, Map<String, Object> input) throws IOException {
            Preview pv = new Preview();
            switch (toolName) {
                case "Write" -> {
                    Path path = s.guardPath(input.get("file_path"));
                    pv.displayPath = s.root.relativize(path).toString();
                    pv.isNewFile = !Files.exists(path);
                    String before = pv.isNewFile ? "" : Files.readString(path, StandardCharsets.UTF_8);
                    String after = String.valueOf(input.get("content"));
                    pv.diff = Diff.unified(before, after, pv.displayPath);
                    pv.note = eolNote(before, after, pv.isNewFile);
                }
                case "Edit" -> {
                    Path path = s.guardPath(input.get("file_path"));
                    pv.displayPath = s.root.relativize(path).toString();
                    if (!Files.exists(path)) throw new Rejection("Edit target does not exist: " + pv.displayPath);
                    String before = Files.readString(path, StandardCharsets.UTF_8);
                    String oldS = String.valueOf(input.get("old_string"));
                    String newS = String.valueOf(input.get("new_string"));
                    boolean replaceAll = Boolean.TRUE.equals(input.get("replace_all"));
                    int count = countOccurrences(before, oldS);
                    if (count == 0 && before.contains("\r\n") && !oldS.contains("\r\n")) {
                        String crlfOld = oldS.replace("\n", "\r\n");
                        int crlfCount = countOccurrences(before, crlfOld);
                        if (crlfCount > 0) {
                            oldS = crlfOld;
                            newS = newS.replace("\r\n", "\n").replace("\n", "\r\n");
                            count = crlfCount;
                            pv.note = "Matched after LF -> CRLF normalization (file uses CRLF).";
                        }
                    }
                    if (count == 0) throw new Rejection("old_string was not found in " + pv.displayPath + ". Re-read the file and retry.");
                    if (count > 1 && !replaceAll) throw new Rejection("old_string occurs " + count + " times in " + pv.displayPath + "; provide more context or set replace_all.");
                    String after = replaceAll ? before.replace(oldS, newS) : replaceFirstLiteral(before, oldS, newS);
                    pv.diff = Diff.unified(before, after, pv.displayPath);
                    String eol = eolNote(before, after, false);
                    if (eol != null) pv.note = pv.note == null ? eol : pv.note + " " + eol;
                }
                case "MultiEdit", "NotebookEdit" -> throw new Rejection(toolName + " is not supported by the IDE approval PoC.");
                default -> {
                    pv.displayPath = "(" + toolName + ")";
                    pv.diff = Json.write(input);
                    pv.note = "Non-file tool; showing raw input.";
                }
            }
            return pv;
        }

        static String eol(String s) {
            int crlf = 0, lf = 0;
            for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') { if (i > 0 && s.charAt(i - 1) == '\r') crlf++; else lf++; }
            if (crlf == 0 && lf == 0) return "none";
            return crlf > 0 && lf > 0 ? "MIXED" : (crlf > 0 ? "CRLF" : "LF");
        }

        /** Diff ignores line endings; report them separately so EOL-only changes are still visible. */
        static String eolNote(String before, String after, boolean newFile) {
            String b = eol(before), a = eol(after);
            if (newFile) return "Line endings: " + a + ".";
            if (a.equals(b) || a.equals("none")) return null;
            return "Line endings change: " + b + " -> " + a + " (diff ignores EOL).";
        }

        static int countOccurrences(String s, String sub) {
            if (sub.isEmpty()) return 0;
            int c = 0;
            for (int i = s.indexOf(sub); i >= 0; i = s.indexOf(sub, i + sub.length())) c++;
            return c;
        }

        static String replaceFirstLiteral(String s, String target, String replacement) {
            int i = s.indexOf(target);
            return s.substring(0, i) + replacement + s.substring(i + target.length());
        }
    }

    // ------------------------------------------------------------------ minimal unified diff (LCS)

    static final class Diff {
        static String unified(String before, String after, String name) {
            List<String> a = lines(before), b = lines(after);
            if (a.equals(b)) return "(no textual change)";
            if ((long) a.size() * b.size() > 4_000_000L) return "(file too large for inline diff: " + a.size() + " -> " + b.size() + " lines)";
            int n = a.size(), m = b.size();
            int[][] lcs = new int[n + 1][m + 1];
            for (int i = n - 1; i >= 0; i--)
                for (int j = m - 1; j >= 0; j--)
                    lcs[i][j] = a.get(i).equals(b.get(j)) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            List<String[]> ops = new ArrayList<>();          // {op, text, aLine, bLine}
            int i = 0, j = 0;
            while (i < n || j < m) {
                if (i < n && j < m && a.get(i).equals(b.get(j))) { ops.add(new String[] {" ", a.get(i), "" + (i + 1), "" + (j + 1)}); i++; j++; }
                else if (j < m && (i == n || lcs[i][j + 1] >= lcs[i + 1][j])) { ops.add(new String[] {"+", b.get(j), "", "" + (j + 1)}); j++; }
                else { ops.add(new String[] {"-", a.get(i), "" + (i + 1), ""}); i++; }
            }
            StringBuilder sb = new StringBuilder("--- a/" + name + "\n+++ b/" + name + "\n");
            int ctx = 3;
            int k = 0;
            while (k < ops.size()) {
                if (ops.get(k)[0].equals(" ")) { k++; continue; }
                int start = Math.max(0, k - ctx);
                int end = k;
                int lastChange = k;
                while (end < ops.size() && (end - lastChange) <= ctx * 2) {
                    if (!ops.get(end)[0].equals(" ")) lastChange = end;
                    end++;
                }
                end = Math.min(ops.size(), lastChange + ctx + 1);
                sb.append("@@ ").append(hunkHeader(ops, start, end)).append(" @@\n");
                for (int x = start; x < end; x++) sb.append(ops.get(x)[0]).append(ops.get(x)[1]).append('\n');
                k = end;
            }
            return sb.toString().stripTrailing();
        }

        private static String hunkHeader(List<String[]> ops, int start, int end) {
            int aStart = -1, bStart = -1, aLen = 0, bLen = 0;
            for (int x = start; x < end; x++) {
                String[] op = ops.get(x);
                if (!op[2].isEmpty()) { if (aStart < 0) aStart = Integer.parseInt(op[2]); aLen++; }
                if (!op[3].isEmpty()) { if (bStart < 0) bStart = Integer.parseInt(op[3]); bLen++; }
            }
            return "-" + Math.max(aStart, 0) + "," + aLen + " +" + Math.max(bStart, 0) + "," + bLen;
        }

        private static List<String> lines(String s) {
            List<String> out = new ArrayList<>();
            if (s.isEmpty()) return out;
            for (String line : s.split("\n", -1)) out.add(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
            if (out.get(out.size() - 1).isEmpty()) out.remove(out.size() - 1);
            return out;
        }
    }

    // ------------------------------------------------------------------ helpers

    static Map<String, Object> rpcResult(Object id, Object result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("result", result);
        return m;
    }

    static Map<String, Object> rpcError(Object id, int code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("error", Map.of("code", code, "message", message));
        return m;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    static void send(HttpExchange ex, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        if (status == 202 || bytes.length == 0) { ex.sendResponseHeaders(status, -1); return; }
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    synchronized void log(Map<String, ?> record) {
        if (logFile == null) return;
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("ts", OffsetDateTime.now().toString());
        line.putAll(record);
        try {
            Files.writeString(logFile, Json.write(line) + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("log failed: " + e);
        }
    }

    // ------------------------------------------------------------------ tiny JSON (no dependencies)

    static final class Json {
        private final String s;
        private int pos;

        private Json(String s) { this.s = s; }

        static Object parse(String text) {
            Json p = new Json(text);
            p.ws();
            Object v = p.value();
            p.ws();
            if (p.pos != p.s.length()) throw p.err("Trailing characters");
            return v;
        }

        private Object value() {
            ws();
            if (pos >= s.length()) throw err("Unexpected end");
            char c = s.charAt(pos);
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': expect("true"); return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null"); return null;
                default: return number();
            }
        }

        private Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++;
            ws();
            if (peek('}')) { pos++; return m; }
            while (true) {
                ws();
                String k = string();
                ws();
                if (!peek(':')) throw err("Expected ':'");
                pos++;
                m.put(k, value());
                ws();
                if (peek(',')) { pos++; continue; }
                if (peek('}')) { pos++; return m; }
                throw err("Expected ',' or '}'");
            }
        }

        private List<Object> array() {
            List<Object> l = new ArrayList<>();
            pos++;
            ws();
            if (peek(']')) { pos++; return l; }
            while (true) {
                l.add(value());
                ws();
                if (peek(',')) { pos++; continue; }
                if (peek(']')) { pos++; return l; }
                throw err("Expected ',' or ']'");
            }
        }

        private String string() {
            if (!peek('"')) throw err("Expected string");
            pos++;
            StringBuilder sb = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                if (pos >= s.length()) break;
                char e = s.charAt(pos++);
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (pos + 4 > s.length()) throw err("Bad unicode escape");
                        sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        pos += 4;
                        break;
                    default: throw err("Bad escape");
                }
            }
            throw err("Unterminated string");
        }

        private Object number() {
            int start = pos;
            while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) pos++;
            String n = s.substring(start, pos);
            if (n.isEmpty()) throw err("Unexpected character '" + s.charAt(pos) + "'");
            if (n.contains(".") || n.contains("e") || n.contains("E")) return Double.parseDouble(n);
            long l = Long.parseLong(n);
            return (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) ? (Object) (int) l : (Object) l;
        }

        private void expect(String word) {
            if (!s.startsWith(word, pos)) throw err("Expected " + word);
            pos += word.length();
        }

        private boolean peek(char c) { return pos < s.length() && s.charAt(pos) == c; }

        private void ws() { while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++; }

        private RuntimeException err(String msg) { return new IllegalArgumentException(msg + " at " + pos); }

        static String write(Object v) {
            StringBuilder sb = new StringBuilder();
            write(v, sb);
            return sb.toString();
        }

        private static void write(Object v, StringBuilder sb) {
            if (v == null) sb.append("null");
            else if (v instanceof String str) quote(str, sb);
            else if (v instanceof Boolean || v instanceof Integer || v instanceof Long) sb.append(v);
            else if (v instanceof Double d) sb.append(d.isNaN() || d.isInfinite() ? "null" : (d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf(d.longValue()) : d.toString()));
            else if (v instanceof Number num) sb.append(num);
            else if (v instanceof Map<?, ?> m) {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    quote(String.valueOf(e.getKey()), sb);
                    sb.append(':');
                    write(e.getValue(), sb);
                }
                sb.append('}');
            } else if (v instanceof Iterable<?> it) {
                sb.append('[');
                boolean first = true;
                for (Object o : it) { if (!first) sb.append(','); first = false; write(o, sb); }
                sb.append(']');
            } else quote(String.valueOf(v), sb);
        }

        private static void quote(String str, StringBuilder sb) {
            sb.append('"');
            for (int i = 0; i < str.length(); i++) {
                char c = str.charAt(i);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    case '\b' -> sb.append("\\b");
                    case '\f' -> sb.append("\\f");
                    default -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
                }
            }
            sb.append('"');
        }
    }
}
