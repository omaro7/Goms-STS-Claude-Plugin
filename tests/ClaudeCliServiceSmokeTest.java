import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import kr.co.goms.sts.claude.service.ClaudeCliService;

public final class ClaudeCliServiceSmokeTest {
    public static void main(String[] args) throws Exception {
        ClaudeCliService service = new ClaudeCliService();
        UUID session = UUID.randomUUID();
        String executable = Path.of(args[0], "tests", "fake-claude.sh").toString();
        Path root = Path.of(args[0]);
        assertRun(service, executable, root, session, false, "hello", "--session-id");
        assertRun(service, executable, root, session, true, "follow-up", "--resume");
        assertRun(service, executable, root, session, true, "FAIL", "ERROR");
        assertRun(service, executable, root, session, true, "SLOW", "CANCEL");
        // V1.2: prompt goes through stdin (quotes and newlines survive), not as an argument
        assertRun(service, executable, root, session, true, "say \"hi\"\nline2", "STDIN: say \"hi\"");
        assertRun(service, executable, root, session, true, "plan", "--permission-mode plan");
        ClaudeCliService.EditMode edit = new ClaudeCliService.EditMode(root.resolve("mcp.json"), root.resolve("ask.json"), "mcp__goms_ide__approve");
        assertEdit(service, executable, root, session, edit, "--permission-prompt-tool mcp__goms_ide__approve");
        assertEdit(service, executable, root, session, edit, "--tools Read,Glob,Grep,Edit,Write");
        assertEdit(service, executable, root, session, edit, "--strict-mcp-config");
        assertEdit(service, executable, root, session, edit, "--settings " + root.resolve("ask.json"));
        assertEdit(service, executable, root, session, edit, "--permission-mode default");
        assertEdit(service, executable, root, session, edit, "MCP_TOOL_TIMEOUT=1800000");
        System.out.println("ALL CLI SERVICE TESTS PASSED");
    }
    private static void assertEdit(ClaudeCliService service, String executable, Path root, UUID session,
            ClaudeCliService.EditMode edit, String expected) throws Exception {
        assertRun(service, executable, root, session, true, "edit please", expected, edit);
    }

    private static void assertRun(ClaudeCliService service, String executable, Path root, UUID session,
            boolean resume, String prompt, String expected) throws Exception {
        assertRun(service, executable, root, session, resume, prompt, expected, null);
    }

    private static void assertRun(ClaudeCliService service, String executable, Path root, UUID session,
            boolean resume, String prompt, String expected, ClaudeCliService.EditMode edit) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> reply = new AtomicReference<>("");
        service.ask(executable, root, prompt, session, resume, edit, new ClaudeCliService.Listener() {
            @Override public void onSuccess(String response) { reply.set(response); done.countDown(); }
            @Override public void onFailure(String error) { reply.set("ERROR: " + error); done.countDown(); }
            @Override public void onCancelled() { reply.set("CANCEL"); done.countDown(); }
        });
        if (prompt.equals("SLOW")) { Thread.sleep(400); service.cancel(); }
        if (!done.await(10, TimeUnit.SECONDS)) throw new AssertionError("run timed out");
        if (!reply.get().contains(expected)) throw new AssertionError("Expected " + expected + ", got " + reply.get());
        System.out.println("OK: " + prompt.replace("\n", "\\n") + " -> " + expected);
        Thread.sleep(100); // let worker clear final state before next request
    }
}
