import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import kr.co.goms.sts.claude.approval.ApprovalBroker;
import kr.co.goms.sts.claude.approval.ApprovalDecision;
import kr.co.goms.sts.claude.approval.ApprovalRejection;
import kr.co.goms.sts.claude.approval.ApprovalRequest;
import kr.co.goms.sts.claude.approval.ChangeRecord;
import kr.co.goms.sts.claude.approval.EditInputRewriter;
import kr.co.goms.sts.claude.approval.EditPreview;
import kr.co.goms.sts.claude.approval.Json;
import kr.co.goms.sts.claude.approval.McpApprovalServer;
import kr.co.goms.sts.claude.approval.PathGuard;

/** Eclipse-free tests for the V1.2 approval core. Run: java -cp out ApprovalCoreSmokeTest */
public final class ApprovalCoreSmokeTest {

    static int passed;

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("goms-approval-test").toRealPath();
        Files.createDirectories(root.resolve("src"));
        Files.createDirectories(root.resolve(".git"));
        Path hello = root.resolve("src/Hello.java");
        Files.writeString(hello, "public class Hello {\r\n    void a() {\r\n        int x = 1;\r\n    }\r\n}\r\n");

        pathGuard(root);
        preview(root, hello);
        rewriter();
        rewriterRandomized();
        broker(root, hello);
        server(root);
        buildTool(root);
        System.out.println("ALL APPROVAL CORE TESTS PASSED (" + passed + " checks)");
    }

    static void check(String name, boolean ok) {
        if (!ok) throw new AssertionError("FAILED: " + name);
        passed++;
        System.out.println("OK: " + name);
    }

    static void rejects(String name, ThrowingRunnable r) {
        try { r.run(); } catch (ApprovalRejection e) { check(name + " -> " + e.getMessage(), true); return; } catch (Exception e) { throw new AssertionError(name + ": " + e); }
        throw new AssertionError("FAILED (not rejected): " + name);
    }

    interface ThrowingRunnable { void run() throws Exception; }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    // ------------------------------------------------------------------

    static void pathGuard(Path root) throws Exception {
        check("relative path inside root", PathGuard.resolve(root, "src/New.java").equals(root.resolve("src/New.java")));
        rejects("outside root", () -> PathGuard.resolve(root, "../evil.txt"));
        rejects("absolute outside root", () -> PathGuard.resolve(root, root.getParent().resolve("x.txt").toString()));
        rejects(".git blocked", () -> PathGuard.resolve(root, ".git/config"));
        rejects(".claude blocked", () -> PathGuard.resolve(root, ".claude/settings.json"));
        rejects("directory", () -> PathGuard.resolve(root, "src"));
        rejects("missing path", () -> PathGuard.resolve(root, null));
        Path outside = Files.createTempDirectory("goms-outside");
        try {
            Files.createSymbolicLink(root.resolve("link"), outside);
            rejects("symlink escape", () -> PathGuard.resolve(root, "link/x.txt"));
        } catch (UnsupportedOperationException | java.io.IOException e) {
            System.out.println("SKIP: symlink escape (" + e + ")");
        }
    }

    static void preview(Path root, Path hello) throws Exception {
        ApprovalRequest r = EditPreview.build(root, "Edit",
                map("file_path", hello.toString(), "old_string", "        int x = 1;\n    }", "new_string", "        int x = 2;\n    }", "replace_all", false),
                "t1", StandardCharsets.UTF_8);
        check("CRLF fallback match", r.after().contains("int x = 2;\r\n    }") && r.note() != null && r.note().contains("CRLF"));
        check("CRLF preserved", !r.after().replace("\r\n", "").contains("\n"));
        ApprovalRequest w = EditPreview.build(root, "Write", map("file_path", "src/G.java", "content", "class G {}\n"), null, StandardCharsets.UTF_8);
        check("Write new file preview", w.newFile() && w.before().isEmpty() && w.displayPath().replace('\\', '/').equals("src/G.java"));
        rejects("old_string missing", () -> EditPreview.build(root, "Edit", map("file_path", "src/Hello.java", "old_string", "nope", "new_string", "x"), null, StandardCharsets.UTF_8));
        rejects("old_string ambiguous", () -> EditPreview.build(root, "Edit", map("file_path", "src/Hello.java", "old_string", "    ", "new_string", "  "), null, StandardCharsets.UTF_8));
        rejects("other tool", () -> EditPreview.build(root, "Bash", map("file_path", "src/Hello.java"), null, StandardCharsets.UTF_8));
        rejects("MultiEdit", () -> EditPreview.build(root, "MultiEdit", map("file_path", "src/Hello.java"), null, StandardCharsets.UTF_8));
    }

    /** Applies an Edit input exactly like Claude Code's Edit tool would. */
    static String apply(String before, Map<String, Object> in) {
        String o = (String) in.get("old_string"), n = (String) in.get("new_string");
        int i = before.indexOf(o);
        if (i < 0 || before.indexOf(o, i + 1) >= 0) throw new AssertionError("old_string not unique: [" + o + "]");
        return before.substring(0, i) + n + before.substring(i + o.length());
    }

    static ApprovalRequest editReq(String before, String after) {
        return new ApprovalRequest("Edit", null, map("file_path", "F.java", "old_string", "?", "new_string", "?", "replace_all", false),
                Path.of("F.java"), "F.java", false, before, after, null);
    }

    static void rewriter() throws Exception {
        String before = "a\nb\nc\nb\nd\n";
        String claudeAfter = "a\nb\nC\nb\nd\n";
        // reviewer additionally changes the first line (outside Claude's region)
        String edited = "A\nb\nC\nb\nd\n";
        Map<String, Object> u = EditInputRewriter.rewrite(editReq(before, claudeAfter), edited);
        check("rewrite covers edits outside region", apply(before, u).equals(edited));
        check("rewrite unchanged -> original input", EditInputRewriter.rewrite(editReq(before, claudeAfter), null).get("old_string").equals("?"));
        // ambiguous line 'b' changed -> must expand context until unique
        String edited2 = "a\nb\nc\nB\nd\n";
        check("rewrite expands until unique", apply(before, EditInputRewriter.rewrite(editReq(before, claudeAfter), edited2)).equals(edited2));
        // pure insertion of a new line between identical lines
        String edited3 = "a\nb\nc\nb\nnew\nd\n";
        check("rewrite pure insertion", apply(before, EditInputRewriter.rewrite(editReq(before, claudeAfter), edited3)).equals(edited3));
        // deletion of the last line, CRLF file
        String crlf = "x\r\ny\r\nz\r\n";
        check("rewrite CRLF deletion", apply(crlf, EditInputRewriter.rewrite(editReq(crlf, "x\r\nY\r\nz\r\n"), "x\r\ny\r\n")).equals("x\r\ny\r\n"));
        // no trailing newline
        String nt = "one\ntwo";
        check("rewrite without trailing newline", apply(nt, EditInputRewriter.rewrite(editReq(nt, "one\n2"), "one\nTWO!")).equals("one\nTWO!"));
        rejects("rewrite identical to current", () -> EditInputRewriter.rewrite(editReq(before, claudeAfter), before));
        ApprovalRequest write = new ApprovalRequest("Write", null, map("file_path", "W.java", "content", "old"), Path.of("W.java"), "W.java", true, "", "old", null);
        check("rewrite Write content", EditInputRewriter.rewrite(write, "new").get("content").equals("new"));
    }

    static void rewriterRandomized() throws Exception {
        Random rnd = new Random(42);
        String[] pool = {"x", "y", "int a = 1;", "", "}", "    return;", "x"};
        int n = 0;
        for (int iter = 0; iter < 3000; iter++) {
            List<String> lines = new ArrayList<>();
            int count = 1 + rnd.nextInt(8);
            for (int i = 0; i < count; i++) lines.add(pool[rnd.nextInt(pool.length)]);
            String eol = rnd.nextBoolean() ? "\n" : "\r\n";
            String before = String.join(eol, lines) + (rnd.nextBoolean() ? eol : "");
            List<String> mod = new ArrayList<>(lines);
            int ops = 1 + rnd.nextInt(3);
            for (int k = 0; k < ops; k++) {
                int op = rnd.nextInt(3);
                if (op == 0 && !mod.isEmpty()) mod.set(rnd.nextInt(mod.size()), "changed" + k);
                else if (op == 1) mod.add(rnd.nextInt(mod.size() + 1), "inserted" + k);
                else if (mod.size() > 1) mod.remove(rnd.nextInt(mod.size()));
            }
            String edited = String.join(eol, mod) + (rnd.nextBoolean() ? eol : "");
            if (edited.equals(before) || before.isEmpty()) continue;   // empty file: rejected by design (tested above)
            Map<String, Object> u = EditInputRewriter.rewrite(editReq(before, before + "?"), edited);
            String got = apply(before, u);
            if (!got.equals(edited)) throw new AssertionError("random rewrite mismatch\nbefore=" + before + "\nedited=" + edited + "\ngot=" + got + "\nin=" + u);
            n++;
        }
        check("randomized rewrite round-trips (" + n + " cases)", n > 2000);
    }

    // ------------------------------------------------------------------

    static void broker(Path root, Path hello) throws Exception {
        LinkedBlockingQueue<ApprovalRequest> shown = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<CompletableFuture<ApprovalDecision>> futures = new LinkedBlockingQueue<>();
        List<String> events = new ArrayList<>();
        List<ChangeRecord> approved = new ArrayList<>();
        ApprovalBroker broker = new ApprovalBroker(req -> {
            CompletableFuture<ApprovalDecision> f = new CompletableFuture<>();
            shown.add(req);
            futures.add(f);
            return f;
        }, new ApprovalBroker.Listener() {
            @Override public synchronized void onEvent(String m) { events.add(m); }
            @Override public synchronized void onApproved(ChangeRecord r) { approved.add(r); }
        }, null);

        Map<String, Object> editArgs = map("tool_name", "Edit", "tool_use_id", "u1",
                "input", map("file_path", hello.toString(), "old_string", "int x = 1;", "new_string", "int x = 3;", "replace_all", false));

        check("deny when no run active", "deny".equals(broker.decide(editArgs).get("behavior")));
        broker.beginRun(root);

        // 1. reject with feedback
        CompletableFuture<Map<String, Object>> d1 = CompletableFuture.supplyAsync(() -> broker.decide(editArgs));
        futures.poll(5, TimeUnit.SECONDS).complete(ApprovalDecision.deny("use a constant"));
        Map<String, Object> r1 = d1.get(5, TimeUnit.SECONDS);
        check("reject returns feedback", "deny".equals(r1.get("behavior")) && "use a constant".equals(r1.get("message")));
        shown.clear();

        // 2. approve with edits -> updatedInput rebuilt
        CompletableFuture<Map<String, Object>> d2 = CompletableFuture.supplyAsync(() -> broker.decide(editArgs));
        ApprovalRequest req2 = shown.poll(5, TimeUnit.SECONDS);
        String edited = req2.after().replace("int x = 3;", "final int x = 3;");
        futures.poll(5, TimeUnit.SECONDS).complete(ApprovalDecision.allow(ApprovalDecision.Kind.ALLOW, edited));
        Map<String, Object> r2 = d2.get(5, TimeUnit.SECONDS);
        Map<String, Object> up = Json.asMap(r2.get("updatedInput"));
        check("approve with edit -> allow", "allow".equals(r2.get("behavior")));
        check("updatedInput reproduces reviewer text", apply(Files.readString(hello), up).equals(edited));
        check("snapshot recorded", approved.size() == 1 && approved.get(0).existedBefore()
                && new String(approved.get(0).beforeBytes(), StandardCharsets.UTF_8).equals(Files.readString(hello)));

        // 3. allow file -> next request for same file is remembered (no dialog)
        CompletableFuture<Map<String, Object>> d3 = CompletableFuture.supplyAsync(() -> broker.decide(editArgs));
        futures.poll(5, TimeUnit.SECONDS).complete(ApprovalDecision.allow(ApprovalDecision.Kind.ALLOW_FILE, null));
        check("allow file", "allow".equals(d3.get(5, TimeUnit.SECONDS).get("behavior")));
        int before = futures.size();
        Map<String, Object> r4 = broker.decide(editArgs);
        check("remembered file needs no dialog", "allow".equals(r4.get("behavior")) && futures.size() == before);
        check("original input passed through", Json.asMap(r4.get("updatedInput")).get("new_string").equals("int x = 3;"));

        // 4. auto-deny outside root, no dialog
        Map<String, Object> r5 = broker.decide(map("tool_name", "Write", "input", map("file_path", "../x.txt", "content", "x")));
        check("outside root auto-denied", "deny".equals(r5.get("behavior")) && futures.size() == before);

        // 5. cancel pending
        Map<String, Object> newFileArgs = map("tool_name", "Write", "input", map("file_path", "src/New.java", "content", "class New {}"));
        CompletableFuture<Map<String, Object>> d6 = CompletableFuture.supplyAsync(() -> broker.decide(newFileArgs));
        futures.poll(5, TimeUnit.SECONDS);
        broker.cancelPending("cancelled");
        Map<String, Object> r6 = d6.get(5, TimeUnit.SECONDS);
        check("cancelPending denies waiting request", "deny".equals(r6.get("behavior")) && "cancelled".equals(r6.get("message")));

        // 6. allow all + new file snapshot (existedBefore=false)
        CompletableFuture<Map<String, Object>> d7 = CompletableFuture.supplyAsync(() -> broker.decide(newFileArgs));
        futures.poll(5, TimeUnit.SECONDS).complete(ApprovalDecision.allow(ApprovalDecision.Kind.ALLOW_ALL, null));
        check("allow all", "allow".equals(d7.get(5, TimeUnit.SECONDS).get("behavior")));
        check("new file record not existed", !broker.lastChange().existedBefore() && broker.lastChange().revertible());
        check("approved keys tracked", broker.approvedKeysThisRun().contains(ApprovalBroker.key(root.resolve("src/New.java"))));
        broker.endRun();
        check("deny after endRun", "deny".equals(broker.decide(editArgs).get("behavior")));

        broker.beginRun(root);
        CompletableFuture<Map<String, Object>> d8 = CompletableFuture.supplyAsync(() -> broker.decide(editArgs));
        CompletableFuture<ApprovalDecision> f8 = futures.poll(5, TimeUnit.SECONDS);
        check("allow-all memory reset on new run", f8 != null);
        f8.complete(ApprovalDecision.deny(null));
        check("default reject message", String.valueOf(d8.get(5, TimeUnit.SECONDS).get("message")).contains("rejected"));
        broker.endRun();
    }

    static void server(Path root) throws Exception {
        ApprovalBroker broker = new ApprovalBroker(req -> CompletableFuture.completedFuture(ApprovalDecision.allow(ApprovalDecision.Kind.ALLOW, null)),
                new ApprovalBroker.Listener() {
                    @Override public void onEvent(String m) { }
                    @Override public void onApproved(ChangeRecord r) { }
                }, null);
        broker.beginRun(root);
        try (McpApprovalServer server = new McpApprovalServer(broker::decide)) {
            server.start();
            Path cfg = root.resolve("out/mcp.json");
            server.writeMcpConfig(cfg);
            Map<String, Object> conf = Json.asMap(Json.asMap(Json.asMap(Json.parse(Files.readString(cfg))).get("mcpServers")).get("goms_ide"));
            String url = (String) conf.get("url");
            String auth = (String) Json.asMap(conf.get("headers")).get("Authorization");
            check("config written", url.startsWith("http://127.0.0.1:") && auth.startsWith("Bearer "));
            HttpClient http = HttpClient.newHttpClient();

            HttpResponse<String> bad = http.send(HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer nope")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            check("bad token 401", bad.statusCode() == 401);

            String init = post(http, url, auth, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\"}}");
            check("initialize echoes protocol", init.contains("\"protocolVersion\":\"2025-11-25\""));
            HttpResponse<String> note = http.send(HttpRequest.newBuilder(URI.create(url)).header("Authorization", auth)
                    .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            check("notification 202", note.statusCode() == 202);
            check("tools/list", post(http, url, auth, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}").contains("\"name\":\"approve\""));
            String call = post(http, url, auth, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"approve\","
                    + "\"arguments\":{\"tool_name\":\"Write\",\"input\":{\"file_path\":\"src/Srv.java\",\"content\":\"class Srv {}\\n\"}}}}");
            Map<String, Object> result = Json.asMap(Json.asMap(Json.parse(call)).get("result"));
            Object text = Json.asMap(((List<?>) result.get("content")).get(0)).get("text");
            check("tools/call allow over HTTP", "allow".equals(Json.asMap(Json.parse((String) text)).get("behavior")));
            check("permission tool name", McpApprovalServer.PERMISSION_TOOL.equals("mcp__goms_ide__approve"));
        }
        broker.endRun();
    }

    static void buildTool(Path root) throws Exception {
        LinkedBlockingQueue<String> asked = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<CompletableFuture<ApprovalDecision>> answers = new LinkedBlockingQueue<>();
        ApprovalBroker broker = new ApprovalBroker(new ApprovalBroker.Ui() {
            @Override public CompletableFuture<ApprovalDecision> requestApproval(ApprovalRequest r) {
                return CompletableFuture.completedFuture(ApprovalDecision.allow(ApprovalDecision.Kind.ALLOW_ALL, null));
            }
            @Override public CompletableFuture<ApprovalDecision> requestToolApproval(String title, String description) {
                CompletableFuture<ApprovalDecision> f = new CompletableFuture<>();
                asked.add(description);
                answers.add(f);
                return f;
            }
        }, new ApprovalBroker.Listener() {
            @Override public void onEvent(String m) { }
            @Override public void onApproved(ChangeRecord r) { }
        }, null);
        broker.beginRun(root);
        // "approve all edits" must not approve builds
        broker.decide(map("tool_name", "Write", "input", map("file_path", "src/All.java", "content", "x")));
        Map<String, Object> buildArgs = map("tool_name", McpApprovalServer.BUILD_TOOL, "input", map("goal", "test", "test", "CalcTest#add", "module", "demo"));
        CompletableFuture<Map<String, Object>> b1 = CompletableFuture.supplyAsync(() -> broker.decide(buildArgs));
        String description = asked.poll(5, TimeUnit.SECONDS);
        check("build asks even after allow-all edits", description != null);
        check("build description", description.equals("mvn test -Dtest=CalcTest#add  (module demo)"));
        answers.poll(5, TimeUnit.SECONDS).complete(ApprovalDecision.deny("not now"));
        check("build denied", "deny".equals(b1.get(5, TimeUnit.SECONDS).get("behavior")));
        CompletableFuture<Map<String, Object>> b2 = CompletableFuture.supplyAsync(() -> broker.decide(buildArgs));
        asked.poll(5, TimeUnit.SECONDS);
        answers.poll(5, TimeUnit.SECONDS).complete(ApprovalDecision.allow(ApprovalDecision.Kind.ALLOW_ALL, null));
        Map<String, Object> r2 = b2.get(5, TimeUnit.SECONDS);
        check("build allowed for run, input unchanged", "allow".equals(r2.get("behavior")) && Json.asMap(r2.get("updatedInput")).get("goal").equals("test"));
        check("next build remembered", "allow".equals(broker.decide(buildArgs).get("behavior")) && asked.isEmpty());
        broker.endRun();
        broker.beginRun(root);
        CompletableFuture<Map<String, Object>> b3 = CompletableFuture.supplyAsync(() -> broker.decide(buildArgs));
        check("build memory reset on new run", asked.poll(5, TimeUnit.SECONDS) != null);
        answers.poll(5, TimeUnit.SECONDS).complete(ApprovalDecision.deny(null));
        b3.get(5, TimeUnit.SECONDS);
        broker.endRun();

        try (McpApprovalServer server = new McpApprovalServer(broker::decide, a -> {
            if ("bad".equals(a.get("goal"))) throw new IllegalArgumentException("Unsupported goal 'bad'");
            return "Result: BUILD SUCCESS (" + a.get("goal") + ")";
        })) {
            server.start();
            Path cfg = root.resolve("out/mcp2.json");
            server.writeMcpConfig(cfg);
            Map<String, Object> conf = Json.asMap(Json.asMap(Json.asMap(Json.parse(Files.readString(cfg))).get("mcpServers")).get("goms_ide"));
            String url = (String) conf.get("url");
            String auth = (String) Json.asMap(conf.get("headers")).get("Authorization");
            HttpClient http = HttpClient.newHttpClient();
            String list = post(http, url, auth, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
            check("tools/list offers run_maven", list.contains("\"name\":\"run_maven\"") && list.contains("\"enum\":[\"compile\",\"test-compile\",\"test\"]"));
            String ok = post(http, url, auth, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"run_maven\",\"arguments\":{\"goal\":\"compile\"}}}");
            check("run_maven result text", ok.contains("BUILD SUCCESS (compile)") && !ok.contains("isError"));
            String bad = post(http, url, auth, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"run_maven\",\"arguments\":{\"goal\":\"bad\"}}}");
            check("run_maven bad input -> isError", bad.contains("\"isError\":true") && bad.contains("Build not started: Unsupported goal"));
        }
        try (McpApprovalServer plain = new McpApprovalServer(broker::decide)) {
            plain.start();
            Path cfg = root.resolve("out/mcp3.json");
            plain.writeMcpConfig(cfg);
            Map<String, Object> conf = Json.asMap(Json.asMap(Json.asMap(Json.parse(Files.readString(cfg))).get("mcpServers")).get("goms_ide"));
            String list = post(HttpClient.newHttpClient(), (String) conf.get("url"), (String) Json.asMap(conf.get("headers")).get("Authorization"),
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
            check("no build tool unless provided", !list.contains("run_maven"));
        }
    }

    static String post(HttpClient http, String url, String auth, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).header("Authorization", auth).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString()).body();
    }
}
