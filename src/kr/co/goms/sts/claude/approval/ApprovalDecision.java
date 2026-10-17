package kr.co.goms.sts.claude.approval;

/**
 * The reviewer's answer to an {@link ApprovalRequest}.
 *
 * @param kind        what the reviewer chose
 * @param editedAfter full file text after the reviewer's own edits, or null when the proposal was not changed
 * @param feedback    rejection reason sent back to Claude (DENY only)
 */
public record ApprovalDecision(Kind kind, String editedAfter, String feedback) {

    public enum Kind {
        /** Allow this request only. */
        ALLOW,
        /** Allow this request and later requests for the same file during the current run. */
        ALLOW_FILE,
        /** Allow this request and every later Edit/Write during the current run. */
        ALLOW_ALL,
        /** Reject; {@link ApprovalDecision#feedback()} is returned to Claude. */
        DENY
    }

    public static ApprovalDecision allow(Kind kind, String editedAfter) {
        if (kind == Kind.DENY) throw new IllegalArgumentException("use deny()");
        return new ApprovalDecision(kind, editedAfter, null);
    }

    public static ApprovalDecision deny(String feedback) {
        String message = feedback == null || feedback.isBlank() ? "The user rejected this change in the IDE." : feedback.strip();
        return new ApprovalDecision(Kind.DENY, null, message);
    }

    public boolean allowed() {
        return kind != Kind.DENY;
    }
}
