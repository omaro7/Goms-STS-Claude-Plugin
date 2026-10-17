package kr.co.goms.sts.claude.approval;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns the reviewer's edited "after" text back into a tool input (updatedInput) for Claude Code.
 * Write: replaces content. Edit: rebuilds a unique old_string/new_string pair covering every line the
 * reviewer changed, so edits made outside Claude's original region are applied too.
 */
public final class EditInputRewriter {

    private EditInputRewriter() { }

    public static Map<String, Object> rewrite(ApprovalRequest request, String editedAfter) throws ApprovalRejection {
        Map<String, Object> updated = new LinkedHashMap<>(request.input());
        if (editedAfter == null || editedAfter.equals(request.after())) return updated;

        if ("Write".equals(request.toolName())) {
            updated.put("content", editedAfter);
            return updated;
        }
        if (!"Edit".equals(request.toolName())) throw new ApprovalRejection("Cannot apply edited text to " + request.toolName());

        String before = request.before();
        if (editedAfter.equals(before)) throw new ApprovalRejection("The edited text is identical to the current file; nothing to change.");
        if (before.isEmpty()) throw new ApprovalRejection("Cannot express an edit of an empty file as an Edit.");

        int prefix = 0;
        int max = Math.min(before.length(), editedAfter.length());
        while (prefix < max && before.charAt(prefix) == editedAfter.charAt(prefix)) prefix++;
        int suffix = 0;
        while (suffix < max - prefix
                && before.charAt(before.length() - 1 - suffix) == editedAfter.charAt(editedAfter.length() - 1 - suffix)) suffix++;

        int delta = editedAfter.length() - before.length();
        int start = lineStart(before, prefix);
        int endB = lineEnd(before, before.length() - suffix);

        String oldS = before.substring(start, endB);
        while (!occursOnce(before, oldS)) {
            if (start == 0 && endB == before.length()) break;              // whole file is always unique
            start = start == 0 ? 0 : lineStart(before, start - 1);
            endB = endB == before.length() ? endB : lineEnd(before, endB + 1);
            oldS = before.substring(start, endB);
        }
        String newS = editedAfter.substring(start, endB + delta);

        updated.put("old_string", oldS);
        updated.put("new_string", newS);
        updated.put("replace_all", Boolean.FALSE);
        return updated;
    }

    /** True when {@code sub} is non-empty and occurs exactly once, counting overlapping matches. */
    static boolean occursOnce(String s, String sub) {
        if (sub.isEmpty()) return false;
        int i = s.indexOf(sub);
        return i >= 0 && s.indexOf(sub, i + 1) < 0;
    }

    /** Index of the first character of the line containing {@code index}. */
    private static int lineStart(String s, int index) {
        int i = Math.min(index, s.length());
        while (i > 0 && s.charAt(i - 1) != '\n') i--;
        return i;
    }

    /** Index just after the '\n' that ends the line containing {@code index} (or the text end). */
    private static int lineEnd(String s, int index) {
        if (index > 0 && index <= s.length() && s.charAt(index - 1) == '\n') return index;
        int nl = s.indexOf('\n', Math.max(0, index));
        return nl < 0 ? s.length() : nl + 1;
    }
}
