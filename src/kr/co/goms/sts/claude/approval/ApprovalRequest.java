package kr.co.goms.sts.claude.approval;

import java.nio.file.Path;
import java.util.Map;

/**
 * One Claude Code permission request for an Edit/Write, already validated and previewed.
 *
 * @param toolName    "Edit" or "Write"
 * @param toolUseId   Claude's tool_use id (may be null)
 * @param input       original tool input as sent by Claude Code
 * @param file        resolved real path inside the workspace root
 * @param displayPath path relative to the workspace root
 * @param newFile     true when the Write creates a file
 * @param before      current file text ("" for a new file)
 * @param after       file text after the change is applied
 * @param note        optional remark for the reviewer (line endings, CRLF matching), or null
 */
public record ApprovalRequest(String toolName, String toolUseId, Map<String, Object> input, Path file,
        String displayPath, boolean newFile, String before, String after, String note) {
}
