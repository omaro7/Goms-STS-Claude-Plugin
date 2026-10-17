package kr.co.goms.sts.claude.approval;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Builds the before/after text of an Edit or Write so the user can review it as a diff. */
public final class EditPreview {

    private EditPreview() { }

    public static ApprovalRequest build(Path root, String toolName, Map<String, Object> input, String toolUseId,
            Charset charset) throws ApprovalRejection {
        Path file = PathGuard.resolve(root, input.get("file_path"));
        String display = root.relativize(file).toString();
        try {
            switch (toolName) {
                case "Write": {
                    boolean newFile = !Files.exists(file);
                    String before = newFile ? "" : Files.readString(file, charset);
                    Object content = input.get("content");
                    if (content == null) throw new ApprovalRejection("Write has no content.");
                    String after = String.valueOf(content);
                    return new ApprovalRequest(toolName, toolUseId, input, file, display, newFile, before, after,
                            eolNote(before, after, newFile));
                }
                case "Edit": {
                    if (!Files.exists(file)) throw new ApprovalRejection("Edit target does not exist: " + display);
                    String before = Files.readString(file, charset);
                    String oldS = String.valueOf(input.get("old_string"));
                    String newS = String.valueOf(input.get("new_string"));
                    boolean replaceAll = Boolean.TRUE.equals(input.get("replace_all"));
                    String note = null;
                    int count = count(before, oldS);
                    if (count == 0 && before.contains("\r\n") && !oldS.contains("\r\n")) {
                        String crlfOld = oldS.replace("\n", "\r\n");
                        int crlfCount = count(before, crlfOld);
                        if (crlfCount > 0) {
                            oldS = crlfOld;
                            newS = newS.replace("\r\n", "\n").replace("\n", "\r\n");
                            count = crlfCount;
                            note = "Matched after LF -> CRLF normalization (file uses CRLF).";
                        }
                    }
                    if (count == 0) throw new ApprovalRejection("old_string was not found in " + display + ". Re-read the file and retry.");
                    if (count > 1 && !replaceAll)
                        throw new ApprovalRejection("old_string occurs " + count + " times in " + display + "; add more context or set replace_all.");
                    String after = replaceAll ? before.replace(oldS, newS) : replaceFirst(before, oldS, newS);
                    String eol = eolNote(before, after, false);
                    if (eol != null) note = note == null ? eol : note + " " + eol;
                    return new ApprovalRequest(toolName, toolUseId, input, file, display, false, before, after, note);
                }
                default:
                    throw new ApprovalRejection(toolName + " cannot be approved in the IDE (only Edit and Write).");
            }
        } catch (IOException e) {
            throw new ApprovalRejection("Cannot read " + display + ": " + e.getMessage());
        }
    }

    static int count(String s, String sub) {
        if (sub.isEmpty()) return 0;
        int c = 0;
        for (int i = s.indexOf(sub); i >= 0; i = s.indexOf(sub, i + sub.length())) c++;
        return c;
    }

    static String replaceFirst(String s, String target, String replacement) {
        int i = s.indexOf(target);
        return s.substring(0, i) + replacement + s.substring(i + target.length());
    }

    static String eol(String s) {
        int crlf = 0, lf = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') { if (i > 0 && s.charAt(i - 1) == '\r') crlf++; else lf++; }
        }
        if (crlf == 0 && lf == 0) return "none";
        return crlf > 0 && lf > 0 ? "MIXED" : (crlf > 0 ? "CRLF" : "LF");
    }

    static String eolNote(String before, String after, boolean newFile) {
        String b = eol(before), a = eol(after);
        if (newFile) return "New file, line endings: " + a + ".";
        if (a.equals(b) || a.equals("none")) return null;
        return "Line endings change: " + b + " -> " + a + ".";
    }
}
