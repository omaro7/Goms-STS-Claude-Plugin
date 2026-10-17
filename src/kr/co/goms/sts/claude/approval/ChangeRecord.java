package kr.co.goms.sts.claude.approval;

import java.nio.file.Path;
import java.time.Instant;

/**
 * Snapshot taken right before an approved Edit/Write so the change can be reverted.
 * Claude Code writes the file itself, so Eclipse Local History does not have this version.
 *
 * @param file          real path of the changed file
 * @param displayPath   path relative to the workspace root
 * @param toolName      "Edit" or "Write"
 * @param existedBefore false when the approved Write created the file
 * @param beforeBytes   exact bytes before the change (empty when the file did not exist, null if unreadable)
 * @param approvedAt    approval time
 */
public record ChangeRecord(Path file, String displayPath, String toolName, boolean existedBefore,
        byte[] beforeBytes, Instant approvedAt) {

    public boolean revertible() {
        return beforeBytes != null;
    }
}
