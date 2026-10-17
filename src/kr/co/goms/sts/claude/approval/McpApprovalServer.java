package kr.co.goms.sts.claude.approval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Minimal MCP server (Streamable HTTP, JSON responses) exposing one tool, {@value #TOOL_NAME}, used as
 * Claude Code's {@code --permission-prompt-tool}. Binds to 127.0.0.1 on a random port and requires a
 * per-instance bearer token. Verified against Claude Code 2.1.300 in poc/v1.2-approval.
 */
public final class McpApprovalServer implements AutoCloseable {

    public static final String SERVER_NAME = "goms_ide";
    public static final String TOOL_NAME = "approve";
    /** Value for --permission-prompt-tool. */
    public static final String PERMISSION_TOOL = "mcp__" + SERVER_NAME + "__" + TOOL_NAME;
    /** V1.3: whitelisted Maven build tool offered to Claude. */
    public static final String BUILD_TOOL_NAME = "run_maven";
    /** Name Claude Code uses for the build tool in permission requests. */
    public static final String BUILD_TOOL = "mcp__" + SERVER_NAME + "__" + BUILD_TOOL_NAME;
    static final String VERSION = "1.3.0";

    /** Runs a build for Claude; returns the report text. IllegalArgumentException = bad input (reported as tool error). */
    public interface BuildTool {
        String run(Map<String, Object> arguments);
    }

    private final Function<Map<String, Object>, Map<String, Object>> decider;
    private final BuildTool buildTool;
    private final String token = randomToken();
    private MiniHttpServer http;

    /** @param decider maps {tool_name, input, tool_use_id} to an allow/deny decision object */
    public McpApprovalServer(Function<Map<String, Object>, Map<String, Object>> decider) {
        this(decider, null);
    }

    /** @param buildTool optional; when set, the {@value #BUILD_TOOL_NAME} tool is offered to Claude */
    public McpApprovalServer(Function<Map<String, Object>, Map<String, Object>> decider, BuildTool buildTool) {
        this.decider = decider;
        this.buildTool = buildTool;
    }

    public synchronized void start() throws IOException {
        if (http != null) return;
        MiniHttpServer server = new MiniHttpServer(this::handle);
        server.start();
        http = server;
    }

    public synchronized boolean isRunning() {
        return http != null;
    }

    public synchronized String url() {
        if (http == null) throw new IllegalStateException("not started");
        return "http://127.0.0.1:" + http.port() + "/mcp";
    }

    /** Writes the --mcp-config JSON (contains the bearer token; keep it in a per-user folder). */
    public void writeMcpConfig(Path file) throws IOException {
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("type", "http");
        server.put("url", url());
        server.put("headers", Map.of("Authorization", "Bearer " + token));
        writeAtomically(file, Json.write(Map.of("mcpServers", Map.of(SERVER_NAME, server))));
    }

    /** Writes the --settings JSON that forces Edit/Write to ask (ask rules beat allow rules; PoC S6/S11). */
    public static void writeForceAskSettings(Path file) throws IOException {
        writeAtomically(file, "{\"permissions\":{\"ask\":[\"Edit\",\"Write\"]}}");
    }

    private static void writeAtomically(Path file, String content) throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    @Override
    public synchronized void close() {
        if (http != null) http.close();
        http = null;
    }

    // ------------------------------------------------------------------ HTTP / JSON-RPC

    private MiniHttpServer.Response handle(MiniHttpServer.Request req) {
        String path = req.path();
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        if (!"/mcp".equals(path)) return new MiniHttpServer.Response(404, "text/plain", "not found");
        String auth = req.header("authorization");
        if (auth == null || !MessageDigest.isEqual(auth.getBytes(StandardCharsets.UTF_8),
                ("Bearer " + token).getBytes(StandardCharsets.UTF_8))) {
            return new MiniHttpServer.Response(401, "application/json", "{\"error\":\"unauthorized\"}");
        }
        switch (req.method()) {
            case "POST":
                return handlePost(req);
            case "DELETE":
                return new MiniHttpServer.Response(200, null, null);
            default:                                     // GET (SSE stream) is not offered
                return new MiniHttpServer.Response(405, "text/plain", "").header("Allow", "POST, DELETE");
        }
    }

    private MiniHttpServer.Response handlePost(MiniHttpServer.Request req) {
        Object parsed;
        try {
            parsed = Json.parse(new String(req.body(), StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return new MiniHttpServer.Response(400, "application/json", Json.write(rpcError(null, -32700, "Parse error")));
        }
        boolean batch = parsed instanceof List;
        List<?> messages = batch ? (List<?>) parsed : List.of(parsed);
        List<Object> responses = new ArrayList<>();
        Map<String, String> extraHeaders = new LinkedHashMap<>();
        for (Object msg : messages) {
            if (!(msg instanceof Map<?, ?> m)) {
                responses.add(rpcError(null, -32600, "Invalid Request"));
                continue;
            }
            Object method = m.get("method");
            if (method == null || !m.containsKey("id")) continue;   // client response or notification
            responses.add(dispatch(String.valueOf(method), m.get("id"), Json.asMap(m.get("params")), extraHeaders));
        }
        if (responses.isEmpty()) return new MiniHttpServer.Response(202, null, null);
        MiniHttpServer.Response r = new MiniHttpServer.Response(200, "application/json", Json.write(batch ? responses : responses.get(0)));
        extraHeaders.forEach(r::header);
        return r;
    }

    private Map<String, Object> dispatch(String method, Object id, Map<String, Object> params, Map<String, String> responseHeaders) {
        switch (method) {
            case "initialize": {
                Object requested = params.get("protocolVersion");
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("protocolVersion", requested != null ? requested : "2025-06-18");
                result.put("capabilities", Map.of("tools", Map.of("listChanged", false)));
                result.put("serverInfo", Map.of("name", "goms-ide-approval", "version", VERSION));
                result.put("instructions", "Permission broker for the Goms STS plugin. Not meant to be called directly by the model.");
                responseHeaders.put("Mcp-Session-Id", UUID.randomUUID().toString());
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
                List<Object> tools = new ArrayList<>(List.of(tool));
                if (buildTool != null) tools.add(buildToolDescriptor());
                return rpcResult(id, Map.of("tools", tools));
            }
            case "tools/call": {
                String name = String.valueOf(params.get("name"));
                if (buildTool != null && BUILD_TOOL_NAME.equals(name)) return callBuild(id, Json.asMap(params.get("arguments")));
                if (!TOOL_NAME.equals(name)) return rpcError(id, -32602, "Unknown tool: " + name);
                Map<String, Object> decision;
                try {
                    decision = decider.apply(Json.asMap(params.get("arguments")));
                } catch (RuntimeException e) {             // fail closed
                    decision = ApprovalBroker.deny("IDE approval failed: " + e.getMessage());
                }
                return rpcResult(id, Map.of("content", List.of(Map.of("type", "text", "text", Json.write(decision)))));
            }
            default:
                return rpcError(id, -32601, "Method not found: " + method);
        }
    }

    private static Map<String, Object> buildToolDescriptor() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("goal", Map.of("type", "string", "enum", List.of("compile", "test-compile", "test"),
                "description", "compile = main sources, test-compile = main + test sources, test = compile and run tests"));
        props.put("test", Map.of("type", "string",
                "description", "Optional Surefire filter for goal=test, e.g. CalcTest, CalcTest#add, com.acme.*Test"));
        props.put("module", Map.of("type", "string",
                "description", "Optional folder (relative to the working directory) whose pom.xml to build. Required when the workspace has several Maven projects and none is at the root."));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("goal"));
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", BUILD_TOOL_NAME);
        tool.put("description", "Runs a Maven build in the developer's IDE and returns compile errors and failed tests. "
                + "Use it to verify your changes (compile after edits, test to run tests). The developer approves each build; "
                + "full output appears in the IDE console.");
        tool.put("inputSchema", schema);
        return tool;
    }

    private Map<String, Object> callBuild(Object id, Map<String, Object> arguments) {
        String text;
        boolean isError = false;
        try {
            text = buildTool.run(arguments);
        } catch (IllegalArgumentException | IllegalStateException e) {
            text = "Build not started: " + e.getMessage();
            isError = true;
        } catch (RuntimeException e) {
            text = "Build failed to run: " + e;
            isError = true;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", List.of(Map.of("type", "text", "text", text)));
        if (isError) result.put("isError", Boolean.TRUE);
        return rpcResult(id, result);
    }

    private static Map<String, Object> rpcResult(Object id, Object result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("result", result);
        return m;
    }

    private static Map<String, Object> rpcError(Object id, int code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("error", Map.of("code", code, "message", message));
        return m;
    }

    private static String randomToken() {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
