import java.nio.file.Path;

import kr.co.goms.sts.claude.context.EditorContext;
import kr.co.goms.sts.claude.context.EditorContextFormatter;

/** Eclipse-free tests for the V1.1 context block. Run: java -cp out EditorContextFormatterSmokeTest */
public final class EditorContextFormatterSmokeTest {

    static int passed;

    public static void main(String[] args) {
        Path root = Path.of(System.getProperty("java.io.tmpdir"), "ws").toAbsolutePath();
        Path file = root.resolve("demo/src/main/java/com/acme/Hello.java");

        EditorContext sel = new EditorContext(file, "demo", "method Hello.main(String[])", 10, 10, 14,
                "for (int i = 0; i < 3; i++) {\n    System.out.println(\"hi\");\n}\n", false, "java");
        String p = EditorContextFormatter.compose("이 코드를 설명해 줘", sel, root);
        check("block wraps message", p.startsWith("<ide_context>\n") && p.endsWith("</ide_context>\n\n이 코드를 설명해 줘"));
        check("relative path with /", p.contains("File: demo/src/main/java/com/acme/Hello.java  (relative"));
        check("project", p.contains("Eclipse project: demo\n"));
        check("cursor + element", p.contains("Cursor: line 10, in method Hello.main(String[])\n"));
        check("selection lines", p.contains("Selection: lines 10-14 (5 lines)\n"));
        check("code fenced with language", p.contains("```java\nfor (int i = 0;") && p.contains("}\n```\n"));
        check("quotes preserved", p.contains("System.out.println(\"hi\");"));
        check("label", EditorContextFormatter.label(sel, root).equals("Hello.java · L10-14 (5 lines) · method Hello.main(String[])"));

        EditorContext caret = new EditorContext(file, "demo", "class Hello", 3, 0, 0, null, true, "java");
        String c = EditorContextFormatter.compose("q", caret, root);
        check("no selection", c.contains("Selection: none\n") && !c.contains("```"));
        check("unsaved note", c.contains("unsaved changes"));
        check("caret label", EditorContextFormatter.label(caret, root).equals("Hello.java · cursor L3 · class Hello · unsaved"));

        Path outside = Path.of(System.getProperty("java.io.tmpdir"), "other", "X.java").toAbsolutePath();
        EditorContext out = new EditorContext(outside, null, null, 1, 1, 1, "x", false, "java");
        check("outside root flagged", EditorContextFormatter.compose("q", out, root).contains("outside the working directory")
                && EditorContextFormatter.label(out, root).endsWith("outside workspace root"));
        check("single line label", EditorContextFormatter.label(out, root).contains("L1 (1 line)"));

        EditorContext ticks = new EditorContext(file, "demo", null, 1, 1, 3, "/** ```java\n code\n``` */", false, "java");
        String t = EditorContextFormatter.compose("q", ticks, root);
        check("fence longer than inner backticks", t.contains("````java\n/** ```java") && t.contains("``` */\n````\n"));

        String big = "a".repeat(EditorContextFormatter.MAX_SELECTION_CHARS + 500);
        EditorContext large = new EditorContext(file, "demo", null, 1, 1, 1, big, false, "java");
        String l = EditorContextFormatter.compose("q", large, root);
        check("large selection truncated", l.contains("[Selection truncated") && l.length() < big.length() + 1000);

        check("null context = plain message", EditorContextFormatter.compose("plain", null, root).equals("plain"));
        check("null root treated as outside", EditorContextFormatter.compose("q", sel, null).contains("outside the working directory"));
        System.out.println("ALL EDITOR CONTEXT TESTS PASSED (" + passed + " checks)");
    }

    static void check(String name, boolean ok) {
        if (!ok) throw new AssertionError("FAILED: " + name);
        passed++;
        System.out.println("OK: " + name);
    }
}
