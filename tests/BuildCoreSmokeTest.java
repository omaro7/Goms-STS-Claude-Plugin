import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

import kr.co.goms.sts.claude.approval.ApprovalBroker;
import kr.co.goms.sts.claude.approval.ApprovalDecision;
import kr.co.goms.sts.claude.approval.ChangeRecord;
import kr.co.goms.sts.claude.approval.Json;
import kr.co.goms.sts.claude.approval.McpApprovalServer;
import kr.co.goms.sts.claude.build.BuildFixPrompt;
import kr.co.goms.sts.claude.build.BuildProblem;
import kr.co.goms.sts.claude.build.BuildRequest;
import kr.co.goms.sts.claude.build.BuildResult;
import kr.co.goms.sts.claude.build.MavenOutputParser;
import kr.co.goms.sts.claude.build.MavenProjectLocator;
import kr.co.goms.sts.claude.build.MavenRunner;
import kr.co.goms.sts.claude.build.TestFailure;

/** Eclipse-free tests for the V1.3 build core. Run: java -cp out BuildCoreSmokeTest (Linux/macOS/Git Bash for the runner part) */
public final class BuildCoreSmokeTest {

    static int passed;

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("goms-build-test").toRealPath();
        Path demo = root.resolve("demo");
        Files.createDirectories(demo.resolve("src/main/java/com/acme"));
        Files.createDirectories(demo.resolve("src/test/java/com/acme"));
        Files.writeString(demo.resolve("pom.xml"), "<project/>");
        Files.writeString(demo.resolve("src/test/java/com/acme/CalcTest.java"), "class CalcTest {}");
        Path other = root.resolve("other");
        Files.createDirectories(other);
        Files.writeString(other.resolve("pom.xml"), "<project/>");
        Files.createDirectories(root.resolve("demo/target/classes"));
        Files.writeString(root.resolve("demo/target/pom.xml"), "<project/>");   // must be ignored

        compileErrors(demo);
        surefire3(demo);
        surefire2AndSummaryFallback(demo);
        success(demo);
        otherErrors(demo);
        requests(demo);
        locator(root, demo, other);
        fixPrompt(root, demo);
        runner(demo);
        endToEnd(root, demo);
        System.out.println("ALL BUILD CORE TESTS PASSED (" + passed + " checks)");
    }

    static void check(String name, boolean ok) {
        if (!ok) throw new AssertionError("FAILED: " + name);
        passed++;
        System.out.println("OK: " + name);
    }

    static BuildResult parse(Path module, BuildRequest.Goal goal, int exit, String text) {
        MavenOutputParser p = new MavenOutputParser(module);
        for (String l : text.split("\n", -1)) p.accept(l);
        return p.finish(new BuildRequest(module, goal, null), exit, false, false, 1234);
    }

    static void compileErrors(Path demo) {
        String f = demo.resolve("src/main/java/com/acme/Calc.java").toString();
        String out = String.join("\n",
                "[INFO] --- compiler:3.13.0:compile (default-compile) @ demo ---",
                "[INFO] Compiling 2 source files with javac [debug target 17] to target/classes",
                "[WARNING] " + f + ":[3,8] [deprecation] Date(int,int,int) in Date has been deprecated",
                "[INFO] -------------------------------------------------------------",
                "[ERROR] COMPILATION ERROR : ",
                "[INFO] -------------------------------------------------------------",
                "[ERROR] " + f + ":[12,9] cannot find symbol",
                "  symbol:   variable totl",
                "  location: class com.acme.Calc",
                "[ERROR] " + f + ":[20,16] incompatible types: String cannot be converted to int",
                "[INFO] 2 errors ",
                "[INFO] BUILD FAILURE",
                "[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.13.0:compile (default-compile) on project demo: Compilation failure: Compilation failure: ",
                "[ERROR] " + f + ":[12,9] cannot find symbol",
                "[ERROR]   symbol:   variable totl",
                "[ERROR]   location: class com.acme.Calc",
                "[ERROR] " + f + ":[20,16] incompatible types: String cannot be converted to int",
                "[ERROR] -> [Help 1]",
                "[ERROR] To see the full stack trace of the errors, re-run Maven with the -e switch.");
        BuildResult r = parse(demo, BuildRequest.Goal.COMPILE, 1, out);
        check("compile: not success", !r.success());
        check("compile: 2 errors deduplicated", r.errorCount() == 2);
        check("compile: 1 warning", r.problems().size() == 3);
        BuildProblem e1 = r.problems().stream().filter(p -> p.line() == 12).findFirst().orElseThrow();
        check("compile: file/line/col", e1.file().toString().endsWith("Calc.java") && e1.column() == 9 && e1.error());
        check("compile: symbol detail kept", e1.detail().contains("symbol:   variable totl") && e1.detail().contains("location: class com.acme.Calc"));
        check("compile: no noise in otherErrors", r.otherErrors().stream().noneMatch(s -> s.contains("Help") || s.contains("symbol")));
        String rep = r.report(demo.getParent(), 10_000);
        check("compile: report relative paths", rep.contains("- demo/src/main/java/com/acme/Calc.java:12:9: cannot find symbol"));
        check("compile: headline", r.headline().startsWith("BUILD FAILURE · 2 compile errors") && r.headline().contains("1 warning"));
        check("compile: fixable", r.hasFixableIssues());

        // Windows style path "/D:/..." from maven-compiler-plugin on Windows
        BuildResult w = parse(demo, BuildRequest.Goal.COMPILE, 1, "[ERROR] /D:/ws/demo/src/main/java/A.java:[5,1] class, interface, enum, or record expected\n[INFO] BUILD FAILURE");
        check("compile: windows /D:/ path parsed", w.errorCount() == 1 && w.problems().get(0).file().toString().replace('\\', '/').endsWith("D:/ws/demo/src/main/java/A.java"));
    }

    static void surefire3(Path demo) {
        String out = String.join("\n",
                "[INFO] -------------------------------------------------------",
                "[INFO]  T E S T S",
                "[INFO] -------------------------------------------------------",
                "[INFO] Running com.acme.CalcTest",
                "[ERROR] Tests run: 3, Failures: 1, Errors: 1, Skipped: 0, Time elapsed: 0.045 s <<< FAILURE! -- in com.acme.CalcTest",
                "[ERROR] com.acme.CalcTest.add -- Time elapsed: 0.012 s <<< FAILURE!",
                "org.opentest4j.AssertionFailedError: expected: <3> but was: <4>",
                "\tat org.junit.jupiter.api.AssertionUtils.fail(AssertionUtils.java:151)",
                "\tat org.junit.jupiter.api.Assertions.assertEquals(Assertions.java:531)",
                "\tat com.acme.CalcTest.add(CalcTest.java:15)",
                "",
                "[ERROR] com.acme.CalcTest.divide(int)[2] -- Time elapsed: 0.002 s <<< ERROR!",
                "java.lang.ArithmeticException: / by zero",
                "\tat com.acme.Calc.divide(Calc.java:22)",
                "\tat com.acme.CalcTest$Nested.helper(CalcTest.java:40)",
                "\tat com.acme.CalcTest.divide(CalcTest.java:31)",
                "",
                "[INFO] ",
                "[INFO] Results:",
                "[INFO] ",
                "[ERROR] Failures: ",
                "[ERROR]   CalcTest.add:15 expected: <3> but was: <4>",
                "[ERROR] Errors: ",
                "[ERROR]   CalcTest.divide:31 » Arithmetic / by zero",
                "[INFO] ",
                "[ERROR] Tests run: 3, Failures: 1, Errors: 1, Skipped: 0",
                "[INFO] ",
                "[INFO] BUILD FAILURE",
                "[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.2.5:test (default-test) on project demo: There are test failures.");
        BuildResult r = parse(demo, BuildRequest.Goal.TEST, 1, out);
        check("surefire3: totals", r.testsRun() == 3 && r.testsFailed() == 1 && r.testsErrored() == 1);
        check("surefire3: 2 failures from headers", r.failures().size() == 2);
        TestFailure add = r.failures().get(0);
        check("surefire3: failure class/method/line", add.testClass().equals("com.acme.CalcTest") && add.method().equals("add") && add.line() == 15 && !add.error());
        check("surefire3: failure message", add.message().equals("org.opentest4j.AssertionFailedError: expected: <3> but was: <4>"));
        check("surefire3: test file located", add.file() != null && add.file().toString().endsWith("CalcTest.java"));
        TestFailure div = r.failures().get(1);
        check("surefire3: parameterized error, nested class frame line", div.method().equals("divide") && div.error() && div.line() == 40);
        check("surefire3: no otherErrors for test noise", r.otherErrors().isEmpty());
        check("surefire3: headline", r.headline().contains("tests 3 run, 1 failed, 1 errors"));
        check("surefire3: report", r.report(demo.getParent(), 10_000).contains("- com.acme.CalcTest#add (demo/src/test/java/com/acme/CalcTest.java:15) FAILED: org.opentest4j"));
    }

    static void surefire2AndSummaryFallback(Path demo) {
        String v2 = String.join("\n",
                "Running com.acme.CalcTest",
                "Tests run: 2, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.05 sec <<< FAILURE! - in com.acme.CalcTest",
                "[ERROR] add(com.acme.CalcTest)  Time elapsed: 0.01 sec  <<< FAILURE!",
                "java.lang.AssertionError: expected:<3> but was:<4>",
                "\tat com.acme.CalcTest.add(CalcTest.java:15)",
                "[ERROR] Tests run: 2, Failures: 1, Errors: 0, Skipped: 0",
                "[INFO] BUILD FAILURE");
        BuildResult r2 = parse(demo, BuildRequest.Goal.TEST, 1, v2);
        check("surefire2: header form", r2.failures().size() == 1 && r2.failures().get(0).method().equals("add")
                && r2.failures().get(0).line() == 15 && r2.testsRun() == 2);

        String summaryOnly = String.join("\n",
                "[ERROR] Failures: ",
                "[ERROR]   CalcTest.add:15 expected: <3> but was: <4>",
                "[ERROR]   Run 1: CalcTest.flaky:9 boom",
                "[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0",
                "[INFO] BUILD FAILURE");
        BuildResult rs = parse(demo, BuildRequest.Goal.TEST, 1, summaryOnly);
        check("summary fallback used", rs.failures().size() == 1 && rs.failures().get(0).testClass().equals("CalcTest") && rs.failures().get(0).line() == 15);

        // multi-module: totals are summed
        BuildResult mm = parse(demo, BuildRequest.Goal.TEST, 0, "[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 1\n[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0\n[INFO] BUILD SUCCESS");
        check("multi-module totals summed", mm.testsRun() == 10 && mm.testsSkipped() == 1 && mm.success());
    }

    static void success(Path demo) {
        BuildResult r = parse(demo, BuildRequest.Goal.COMPILE, 0, "[INFO] Nothing to compile - all classes are up to date.\n[INFO] BUILD SUCCESS\n[INFO] Total time:  0.812 s");
        check("success", r.success() && r.problems().isEmpty() && r.testsRun() == -1 && !r.hasFixableIssues());
        check("success headline", r.headline().startsWith("BUILD SUCCESS ·"));
    }

    static void otherErrors(Path demo) {
        String out = String.join("\n",
                "[ERROR] Failed to execute goal on project demo: Could not resolve dependencies for project com.acme:demo:jar:1.0: The following artifacts could not be resolved: org.foo:bar:jar:9.9 (absent)",
                "[ERROR] ",
                "[ERROR] -> [Help 1]",
                "[INFO] BUILD FAILURE");
        BuildResult r = parse(demo, BuildRequest.Goal.COMPILE, 1, out);
        check("dependency error captured", r.otherErrors().size() == 1 && r.otherErrors().get(0).contains("Could not resolve dependencies"));
        check("dependency error in report", r.report(demo, 10_000).contains("Maven errors:") && r.hasFixableIssues());
        BuildResult big = parse(demo, BuildRequest.Goal.COMPILE, 1, out);
        check("report truncation", big.report(demo, 120).contains("[report truncated"));
    }

    static void requests(Path demo) {
        BuildRequest t = new BuildRequest(demo, BuildRequest.Goal.TEST, "CalcTest#add");
        check("test args", t.mavenArguments().equals(List.of("-B", "-ntp", "-Dstyle.color=never", "-DtrimStackTrace=true",
                "-Dtest=CalcTest#add", "-Dsurefire.failIfNoSpecifiedTests=false", "test")));
        check("goal parse", BuildRequest.Goal.parse(" Test-Compile ") == BuildRequest.Goal.TEST_COMPILE);
        expectIae("unknown goal", () -> BuildRequest.Goal.parse("deploy"));
        expectIae("filter injection", () -> new BuildRequest(demo, BuildRequest.Goal.TEST, "X & del *"));
        expectIae("filter injection 2", () -> new BuildRequest(demo, BuildRequest.Goal.TEST, "X\" -Dmaven.repo.local=evil"));
        expectIae("filter only for test", () -> new BuildRequest(demo, BuildRequest.Goal.COMPILE, "X"));
        check("describe", t.describe(demo.getParent()).equals("mvn test -Dtest=CalcTest#add  (demo)"));
    }

    static void locator(Path root, Path demo, Path other) throws Exception {
        Path test = demo.resolve("src/test/java/com/acme/CalcTest.java");
        check("nearest pom from editor file", demo.equals(MavenProjectLocator.nearestPomDir(test, root)));
        check("list pom dirs skips target", MavenProjectLocator.listPomDirs(root).equals(List.of(demo, other)));
        check("default module from editor", demo.equals(MavenProjectLocator.defaultModule(root, test)));
        check("ambiguous default -> null", MavenProjectLocator.defaultModule(root, null) == null);
        check("resolve module", demo.equals(MavenProjectLocator.resolveModule(root, "demo")));
        expectIae("module outside root", () -> MavenProjectLocator.resolveModule(root, "../x"));
        expectIae("module without pom", () -> MavenProjectLocator.resolveModule(root, "demo/src"));
        Files.writeString(demo.resolve("mvnw"), "#!/bin/sh\n");
        check("wrapper preferred", demo.resolve("mvnw").equals(MavenProjectLocator.findMaven(demo, root, null)));
        Files.delete(demo.resolve("mvnw"));
        Path pde = Files.createTempDirectory("goms-pde");
        Files.createDirectories(pde.resolve("META-INF"));
        Files.writeString(pde.resolve("META-INF/MANIFEST.MF"), "Manifest-Version: 1.0\n");
        Files.writeString(pde.resolve("plugin.xml"), "<plugin/>");
        check("PDE project explained", MavenProjectLocator.listPomDirs(pde).isEmpty()
                && MavenProjectLocator.noMavenProjectMessage(pde).contains("Eclipse plug-in (PDE) project"));
        check("override must exist", MavenProjectLocator.findMaven(demo, root, root.resolve("nope").toString()) == null);
    }

    static void runner(Path demo) throws Exception {
        if (System.getProperty("os.name").toLowerCase().contains("win")) { System.out.println("SKIP: runner tests need a POSIX shell"); return; }
        Path fake = demo.resolve("fake-mvn.sh");
        Files.writeString(fake, String.join("\n",
                "#!/bin/sh",
                "echo \"args: $*\"",
                "case \"$*\" in *SLOW*) sleep 20;; esac",
                "if [ \"$GOMS_FAIL\" = 1 ]; then",
                "  echo \"[ERROR] $PWD/src/main/java/com/acme/Calc.java:[3,1] ';' expected\"",
                "  echo '[INFO] BUILD FAILURE'; exit 1",
                "fi",
                "echo '[INFO] BUILD SUCCESS'", ""));
        Files.setPosixFilePermissions(fake, PosixFilePermissions.fromString("rwxr-xr-x"));
        MavenRunner runner = new MavenRunner();
        List<String> lines = new ArrayList<>();
        BuildResult ok = runner.run(new BuildRequest(demo, BuildRequest.Goal.TEST, "CalcTest"), fake, lines::add);
        check("runner success", ok.success() && ok.exitCode() == 0);
        check("runner passes whitelisted args", lines.stream().anyMatch(l -> l.equals("args: -B -ntp -Dstyle.color=never -DtrimStackTrace=true -Dtest=CalcTest -Dsurefire.failIfNoSpecifiedTests=false test")));
        check("runner echoes command and headline", lines.get(0).startsWith("> ") && lines.get(lines.size() - 1).startsWith("< BUILD SUCCESS"));

        MavenRunner slow = new MavenRunner();
        Thread t = new Thread(() -> { try { Thread.sleep(500); } catch (InterruptedException ignored) { } slow.cancel(); });
        t.start();
        long s = System.currentTimeMillis();
        BuildResult c = slow.run(new BuildRequest(demo, BuildRequest.Goal.TEST, "SLOW"), fake, null);
        check("runner cancel", c.cancelled() && !c.success() && System.currentTimeMillis() - s < 10_000);

        MavenRunner shortTimeout = new MavenRunner(700);
        BuildResult to = shortTimeout.run(new BuildRequest(demo, BuildRequest.Goal.TEST, "SLOW"), fake, null);
        check("runner timeout", to.timedOut() && !to.success());

        BuildResult missing = new MavenRunner().run(new BuildRequest(demo, BuildRequest.Goal.COMPILE, null), demo.resolve("no-such-mvn"), null);
        check("runner missing executable", !missing.success() && missing.otherErrors().stream().anyMatch(e -> e.contains("Could not start Maven")));
    }

    static void fixPrompt(Path root, Path demo) {
        String f = demo.resolve("src/main/java/com/acme/Calc.java").toString();
        MavenOutputParser p = new MavenOutputParser(demo);
        for (String l : ("[ERROR] " + f + ":[12,9] cannot find symbol\n  symbol:   variable totl\n[INFO] BUILD FAILURE").split("\n")) p.accept(l);
        BuildResult r = p.finish(new BuildRequest(demo, BuildRequest.Goal.TEST, "CalcTest"), 1, false, false, 10);
        String prompt = BuildFixPrompt.compose(r, root);
        check("fix prompt verify instruction", prompt.contains("call run_maven with goal \"test\", test \"CalcTest\", module \"demo\""));
        check("fix prompt report block", prompt.contains("<build_report>\n") && prompt.contains("demo/src/main/java/com/acme/Calc.java:12:9: cannot find symbol")
                && prompt.endsWith("</build_report>"));
        check("fix prompt guards tests/env", prompt.contains("ask before changing the test") && prompt.contains("do not edit files"));
        BuildResult rootBuild = new MavenOutputParser(root).finish(new BuildRequest(root, BuildRequest.Goal.COMPILE, null), 1, false, false, 1);
        check("fix prompt omits module for root build", !BuildFixPrompt.compose(rootBuild, root).contains("module \""));
    }

    /** Claude -> MCP run_maven -> broker approval -> MavenRunner (fake mvn) -> report. */
    static void endToEnd(Path root, Path demo) throws Exception {
        if (System.getProperty("os.name").toLowerCase().contains("win")) return;
        Path fake = demo.resolve("fake-mvn.sh");
        ApprovalBroker broker = new ApprovalBroker(new ApprovalBroker.Ui() {
            @Override public java.util.concurrent.CompletableFuture<ApprovalDecision> requestApproval(kr.co.goms.sts.claude.approval.ApprovalRequest r) {
                return java.util.concurrent.CompletableFuture.completedFuture(ApprovalDecision.deny("no"));
            }
            @Override public java.util.concurrent.CompletableFuture<ApprovalDecision> requestToolApproval(String t, String d) {
                return java.util.concurrent.CompletableFuture.completedFuture(ApprovalDecision.allow(ApprovalDecision.Kind.ALLOW, null));
            }
        }, new ApprovalBroker.Listener() {
            @Override public void onEvent(String m) { }
            @Override public void onApproved(ChangeRecord r) { }
        }, null);
        broker.beginRun(root);
        MavenRunner runner = new MavenRunner();
        McpApprovalServer.BuildTool tool = a -> {
            BuildRequest req = new BuildRequest(MavenProjectLocator.resolveModule(root, (String) a.get("module")),
                    BuildRequest.Goal.parse((String) a.get("goal")), (String) a.get("test"));
            return runner.run(req, fake, null).report(root, BuildFixPrompt.MAX_REPORT_CHARS);
        };
        try (McpApprovalServer server = new McpApprovalServer(broker::decide, tool)) {
            server.start();
            Path cfg = root.resolve("mcp.json");
            server.writeMcpConfig(cfg);
            var conf = Json.asMap(Json.asMap(Json.asMap(Json.parse(Files.readString(cfg))).get("mcpServers")).get("goms_ide"));
            var http = java.net.http.HttpClient.newHttpClient();
            java.util.function.Function<String, String> post = body -> {
                try {
                    return http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create((String) conf.get("url")))
                            .header("Authorization", (String) Json.asMap(conf.get("headers")).get("Authorization"))
                            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build(),
                            java.net.http.HttpResponse.BodyHandlers.ofString()).body();
                } catch (Exception e) { throw new RuntimeException(e); }
            };
            // 1) permission check exactly as Claude Code sends it
            String perm = post.apply("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"approve\",\"arguments\":{\"tool_name\":\"mcp__goms_ide__run_maven\",\"input\":{\"goal\":\"compile\",\"module\":\"demo\"}}}}");
            check("e2e: build permission allowed", perm.contains("\\\"behavior\\\":\\\"allow\\\""));
            // 2) the tool call itself
            String call = post.apply("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"run_maven\",\"arguments\":{\"goal\":\"compile\",\"module\":\"demo\"}}}");
            check("e2e: build ran and reported", call.contains("Command: mvn compile  (demo)") && call.contains("BUILD SUCCESS"));
            String bad = post.apply("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"run_maven\",\"arguments\":{\"goal\":\"compile\",\"module\":\"../etc\"}}}");
            check("e2e: module escape rejected as tool error", bad.contains("\"isError\":true") && bad.contains("outside the workspace root"));
        }
        broker.endRun();
    }

    interface Thrower { void run() throws Exception; }

    static void expectIae(String name, Thrower r) {
        try { r.run(); } catch (IllegalArgumentException e) { check(name + " -> " + e.getMessage(), true); return; } catch (Exception e) { throw new AssertionError(name + ": " + e); }
        throw new AssertionError("FAILED (no exception): " + name);
    }
}
