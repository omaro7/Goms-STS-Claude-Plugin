package kr.co.goms.sts.claude.ui;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.eclipse.compare.CompareConfiguration;
import org.eclipse.compare.IEditableContent;
import org.eclipse.compare.IEncodedStreamContentAccessor;
import org.eclipse.compare.ITypedElement;
import org.eclipse.compare.contentmergeviewer.TextMergeViewer;
import org.eclipse.compare.structuremergeviewer.DiffNode;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import kr.co.goms.sts.claude.approval.ApprovalDecision;
import kr.co.goms.sts.claude.approval.ApprovalRequest;

/**
 * Modeless review dialog for one Claude Edit/Write: left = current file, right = proposal (editable).
 * The decision is delivered exactly once; closing the dialog any other way counts as a rejection.
 */
public final class ApprovalDialog extends Dialog {

    private static final int APPROVE_ID = IDialogConstants.CLIENT_ID + 1;
    private static final int APPROVE_FILE_ID = IDialogConstants.CLIENT_ID + 2;
    private static final int APPROVE_ALL_ID = IDialogConstants.CLIENT_ID + 3;
    private static final int REJECT_ID = IDialogConstants.CLIENT_ID + 4;

    private final ApprovalRequest request;
    private final boolean dirtyInEditor;
    private final Consumer<ApprovalDecision> onDecision;
    private final AtomicBoolean delivered = new AtomicBoolean();
    private CompareConfiguration compareConfig;
    private TextMergeViewer viewer;
    private TextElement proposed;
    private Text feedback;

    public ApprovalDialog(Shell parent, ApprovalRequest request, boolean dirtyInEditor, Consumer<ApprovalDecision> onDecision) {
        super(parent);
        this.request = request;
        this.dirtyInEditor = dirtyInEditor;
        this.onDecision = onDecision;
        setShellStyle((getShellStyle() & ~SWT.APPLICATION_MODAL) | SWT.MODELESS | SWT.RESIZE | SWT.MAX);
        setBlockOnOpen(false);
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("Claude wants to " + (request.newFile() ? "create " : "change ") + request.displayPath());
    }

    @Override
    protected boolean isResizable() {
        return true;
    }

    @Override
    protected Point getInitialSize() {
        return new Point(1100, 720);
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        area.setLayout(new GridLayout(1, false));

        Label header = new Label(area, SWT.WRAP);
        header.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        header.setText(request.toolName() + "  " + request.displayPath() + (request.newFile() ? "   [NEW FILE]" : ""));
        header.setFont(org.eclipse.jface.resource.JFaceResources.getBannerFont());

        if (request.note() != null) info(area, request.note(), SWT.COLOR_DARK_GRAY);
        if (dirtyInEditor) {
            info(area, "This file has unsaved changes in an editor. Save or revert it first, otherwise Claude's write "
                    + "will conflict with your editor buffer.", SWT.COLOR_RED);
        }
        info(area, "Right side is editable: change Claude's proposal before approving if needed.", SWT.COLOR_DARK_GRAY);

        compareConfig = new CompareConfiguration();
        compareConfig.setLeftLabel(request.newFile() ? "(file does not exist yet)" : "Current: " + request.displayPath());
        compareConfig.setRightLabel("Proposed by Claude (editable)");
        compareConfig.setLeftEditable(false);
        compareConfig.setRightEditable(true);

        viewer = new TextMergeViewer(area, SWT.BORDER, compareConfig);
        GridData viewerData = new GridData(SWT.FILL, SWT.FILL, true, true);
        viewerData.heightHint = 450;
        viewer.getControl().setLayoutData(viewerData);
        String type = extension(request.displayPath());
        proposed = new TextElement("Proposed", type, request.after(), true);
        viewer.setInput(new DiffNode(new TextElement("Current", type, request.before(), false), proposed));

        new Label(area, SWT.NONE).setText("Reason if you reject (sent back to Claude so it can revise):");
        feedback = new Text(area, SWT.MULTI | SWT.BORDER | SWT.WRAP | SWT.V_SCROLL);
        GridData fb = new GridData(SWT.FILL, SWT.TOP, true, false);
        fb.heightHint = 48;
        feedback.setLayoutData(fb);
        return area;
    }

    private static void info(Composite parent, String text, int color) {
        Label l = new Label(parent, SWT.WRAP);
        l.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        l.setText(text);
        l.setForeground(parent.getDisplay().getSystemColor(color));
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, APPROVE_ID, "Approve", false);
        createButton(parent, APPROVE_FILE_ID, "Approve file (this run)", false);
        createButton(parent, APPROVE_ALL_ID, "Approve all (this run)", false);
        createButton(parent, REJECT_ID, "Reject", false);
    }

    @Override
    protected void buttonPressed(int buttonId) {
        switch (buttonId) {
            case APPROVE_ID -> deliver(ApprovalDecision.allow(ApprovalDecision.Kind.ALLOW, editedText()));
            case APPROVE_FILE_ID -> deliver(ApprovalDecision.allow(ApprovalDecision.Kind.ALLOW_FILE, editedText()));
            case APPROVE_ALL_ID -> deliver(ApprovalDecision.allow(ApprovalDecision.Kind.ALLOW_ALL, editedText()));
            case REJECT_ID -> deliver(ApprovalDecision.deny(feedback.getText()));
            default -> super.buttonPressed(buttonId);
        }
        close();
    }

    /** The reviewer's edited proposal, or null when the right side was not changed. */
    private String editedText() {
        viewer.flush(new NullProgressMonitor());     // pushes editor text into proposed.setContent(..)
        String text = proposed.text();
        return text.equals(request.after()) ? null : text;
    }

    /** Closed via Esc / window X / externally: reject. */
    @Override
    public boolean close() {
        String reason = feedback != null && !feedback.isDisposed() && !feedback.getText().isBlank()
                ? feedback.getText() : "The user closed the IDE approval dialog without approving.";
        deliver(ApprovalDecision.deny(reason));
        if (compareConfig != null) compareConfig.dispose();
        return super.close();
    }

    /** Closes the dialog from outside (run cancelled / view closed). */
    public void cancel(String reason) {
        deliver(ApprovalDecision.deny(reason));
        Shell shell = getShell();
        if (shell != null && !shell.isDisposed()) close();
    }

    private void deliver(ApprovalDecision decision) {
        if (delivered.compareAndSet(false, true)) onDecision.accept(decision);
    }

    private static String extension(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? ITypedElement.TEXT_TYPE : path.substring(dot + 1);
    }

    /** In-memory compare element; UTF-8 internally (only used for display/editing, never written to disk). */
    private static final class TextElement implements ITypedElement, IEncodedStreamContentAccessor, IEditableContent {
        private final String name;
        private final String type;
        private final boolean editable;
        private volatile String text;

        TextElement(String name, String type, String text, boolean editable) {
            this.name = name;
            this.type = type;
            this.text = text;
            this.editable = editable;
        }

        String text() { return text; }

        @Override public String getName() { return name; }
        @Override public Image getImage() { return null; }
        @Override public String getType() { return type; }
        @Override public InputStream getContents() { return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)); }
        @Override public String getCharset() { return StandardCharsets.UTF_8.name(); }
        @Override public boolean isEditable() { return editable; }
        @Override public ITypedElement replace(ITypedElement dest, ITypedElement src) { return dest; }
        @Override public void setContent(byte[] newContent) { if (editable) text = new String(newContent, StandardCharsets.UTF_8); }
    }
}
