package kr.co.goms.sts.claude.context;

import java.nio.file.Path;

/** Turns an {@link EditorContext} into a short UI label and into the context block sent to Claude (V1.1). */
public final class EditorContextFormatter {

    /** Larger selections are cut; Claude can still read the whole file from disk. */
    public static final int MAX_SELECTION_CHARS = 20_000;

    private EditorContextFormatter() { }

    /** e.g. "Hello.java · L10-14 (5 lines) · method Hello.main(String[])". */
    public static String label(EditorContext c, Path root) {
        if (c == null) return "No editor";
        StringBuilder sb = new StringBuilder(c.file().getFileName().toString());
        if (c.hasSelection()) {
            sb.append(" · L").append(c.selectionStartLine());
            if (c.selectionEndLine() != c.selectionStartLine()) sb.append('-').append(c.selectionEndLine());
            sb.append(" (").append(c.selectedLineCount()).append(c.selectedLineCount() == 1 ? " line)" : " lines)");
        } else {
            sb.append(" · cursor L").append(c.cursorLine());
        }
        if (c.javaElement() != null) sb.append(" · ").append(c.javaElement());
        if (c.dirty()) sb.append(" · unsaved");
        if (root != null && relative(c.file(), root) == null) sb.append(" · outside workspace root");
        return sb.toString();
    }

    /** The user's message prefixed with an {@code <ide_context>} block. */
    public static String compose(String message, EditorContext c, Path root) {
        if (c == null) return message;
        StringBuilder sb = new StringBuilder();
        sb.append("<ide_context>\n");
        sb.append("The developer is asking from the Spring Tools Suite editor. Treat this as the subject of the request ")
          .append("(\"this\", \"here\", \"the selection\" refer to it). Read the file if you need more context.\n");
        String rel = root == null ? null : relative(c.file(), root);
        if (rel != null) sb.append("File: ").append(rel).append("  (relative to the working directory)\n");
        else sb.append("File: ").append(c.file()).append("  (outside the working directory; you may not be able to read it)\n");
        if (c.projectName() != null) sb.append("Eclipse project: ").append(c.projectName()).append('\n');
        sb.append("Cursor: line ").append(c.cursorLine());
        if (c.javaElement() != null) sb.append(", in ").append(c.javaElement());
        sb.append('\n');
        if (c.hasSelection()) {
            String text = c.selectedText();
            boolean truncated = text.length() > MAX_SELECTION_CHARS;
            if (truncated) text = text.substring(0, MAX_SELECTION_CHARS);
            sb.append("Selection: lines ").append(c.selectionStartLine()).append('-').append(c.selectionEndLine())
              .append(" (").append(c.selectedLineCount()).append(c.selectedLineCount() == 1 ? " line)\n" : " lines)\n");
            String fence = fence(text);
            sb.append(fence).append(c.language() == null ? "" : c.language()).append('\n');
            sb.append(text);
            if (!text.endsWith("\n")) sb.append('\n');
            sb.append(fence).append('\n');
            if (truncated) sb.append("[Selection truncated to the first ").append(MAX_SELECTION_CHARS).append(" characters; read the file for the rest.]\n");
        } else {
            sb.append("Selection: none\n");
        }
        if (c.dirty()) {
            sb.append("Note: the editor has unsaved changes. The selection above comes from the editor buffer; ")
              .append("the file on disk may differ until the developer saves.\n");
        }
        sb.append("</ide_context>\n\n");
        sb.append(message);
        return sb.toString();
    }

    /** Path relative to root with '/' separators, or null when the file is not under root. */
    static String relative(Path file, Path root) {
        Path f = file.toAbsolutePath().normalize();
        Path r = root.toAbsolutePath().normalize();
        if (!f.startsWith(r) || f.equals(r)) return null;
        return r.relativize(f).toString().replace('\\', '/');
    }

    /** A backtick fence longer than any backtick run inside the text (minimum three). */
    static String fence(String text) {
        int longest = 0, run = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '`') longest = Math.max(longest, ++run);
            else run = 0;
        }
        return "`".repeat(Math.max(3, longest + 1));
    }
}
