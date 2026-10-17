package kr.co.goms.sts.claude.approval;

/** A request the IDE denies without asking the user; the message is returned to Claude. */
public final class ApprovalRejection extends Exception {

    private static final long serialVersionUID = 1L;

    public ApprovalRejection(String message) {
        super(message);
    }
}
