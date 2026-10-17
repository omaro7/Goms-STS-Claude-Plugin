package kr.co.goms.sts.claude.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.ISelectionListener;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.dialogs.ElementListSelectionDialog;
import org.eclipse.ui.part.ViewPart;

import kr.co.goms.sts.claude.approval.ApprovalBroker;
import kr.co.goms.sts.claude.approval.ApprovalDecision;
import kr.co.goms.sts.claude.approval.ApprovalRequest;
import kr.co.goms.sts.claude.approval.ChangeRecord;
import kr.co.goms.sts.claude.approval.McpApprovalServer;
import kr.co.goms.sts.claude.build.BuildConsole;
import kr.co.goms.sts.claude.build.BuildFixPrompt;
import kr.co.goms.sts.claude.build.BuildMarkers;
import kr.co.goms.sts.claude.build.BuildRequest;
import kr.co.goms.sts.claude.build.BuildResult;
import kr.co.goms.sts.claude.build.MavenProjectLocator;
import kr.co.goms.sts.claude.build.MavenRunner;
import kr.co.goms.sts.claude.context.EclipseProjectResolver;
import kr.co.goms.sts.claude.context.EditorContext;
import kr.co.goms.sts.claude.context.EditorContextCapture;
import kr.co.goms.sts.claude.context.EditorContextFormatter;
import kr.co.goms.sts.claude.context.WorkspaceRootValidator;
import kr.co.goms.sts.claude.context.WorkspaceSync;
import kr.co.goms.sts.claude.service.ClaudeCliService;

/**
 * Eclipse ViewPart for Claude Code: read-only analysis (plan mode) or edits with per-change approval (V1.2),
 * with the active editor's file/selection attached as context (V1.1) and Maven build/test automation (V1.3).
 */
public final class ClaudeChatView extends ViewPart {

    public static final String ID = "kr.co.goms.sts.claude.ui.ClaudeChatView";
    static final String VERSION = "V1.3.0";
    private static final int MODE_READ_ONLY = 0;
    private static final int MODE_EDIT = 1;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final ClaudeCliService cli = new ClaudeCliService();
    private final EclipseProjectResolver resolver = new EclipseProjectResolver();
    private Display display;
    private Text projectField;
    private Text executableField;
    private Combo modeCombo;
    private Text history;
    private Text promptField;
    private Label status;
    private Button sendButton;
    private Button cancelButton;
    private Button newChatButton;
    private Button revertButton;
    private Button includeContextCheck;
    private Label contextLabel;
    private EditorContext pinnedContext;
    private ISelectionListener selectionListener;
    private IPartListener2 partListener;
    private UUID sessionId = UUID.randomUUID();
    private Path sessionRoot;
    private boolean hasCompletedTurn;
    private boolean running;
    private volatile boolean disposed;

    // V1.2 edit-with-approval infrastructure (created on first edit-mode run)
    private ApprovalBroker broker;
    private McpApprovalServer approvalServer;
    private ClaudeCliService.EditMode editMode;
    private Path stateDir;
    private ApprovalDialog activeDialog;
    private WorkspaceSync.UnapprovedChangeMonitor changeMonitor;

    // V1.3 Maven build / test
    private final MavenRunner buildRunner = new MavenRunner();
    private Text mavenField;
    private Button compileButton;
    private Button testButton;
    private Button fixButton;
    private volatile BuildResult lastBuild;
    private volatile Path runRoot;
    private volatile Path runEditorFile;

    @Override
    public void createPartControl(Composite parent) {
        display = parent.getDisplay();
        parent.setLayout(new GridLayout(1, false));

        Composite projectRow = new Composite(parent, SWT.NONE);
        projectRow.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        projectRow.setLayout(new GridLayout(3, false));
        new Label(projectRow, SWT.NONE).setText("Workspace root");
        projectField = new Text(projectRow, SWT.BORDER);
        projectField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        projectField.addListener(SWT.Modify, event -> refreshContextLabel());
        Button selectProject = new Button(projectRow, SWT.PUSH);
        selectProject.setText("Use selection");
        selectProject.addListener(SWT.Selection, event -> useCurrentProject(true));

        Composite cliRow = new Composite(parent, SWT.NONE);
        cliRow.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        cliRow.setLayout(new GridLayout(3, false));
        new Label(cliRow, SWT.NONE).setText("Claude path");
        executableField = new Text(cliRow, SWT.BORDER);
        executableField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        executableField.setText(ClaudeCliService.defaultExecutable());
        Button selectExecutable = new Button(cliRow, SWT.PUSH);
        selectExecutable.setText("Browse");
        selectExecutable.addListener(SWT.Selection, event -> browseExecutable());

        /*
        Composite mavenRow = new Composite(parent, SWT.NONE);
        mavenRow.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        mavenRow.setLayout(new GridLayout(6, false));
        new Label(mavenRow, SWT.NONE).setText("Maven");
        mavenField = new Text(mavenRow, SWT.BORDER);
        mavenField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        mavenField.setMessage("auto: mvnw, MAVEN_HOME or PATH");
        mavenField.setToolTipText("Leave empty to use the project's mvnw(.cmd), else mvn(.cmd) from MAVEN_HOME/M2_HOME/PATH");
        Button browseMaven = new Button(mavenRow, SWT.PUSH);
        browseMaven.setText("...");
        browseMaven.setToolTipText("Choose mvn.cmd / mvnw.cmd");
        browseMaven.addListener(SWT.Selection, event -> browseMaven());
        compileButton = new Button(mavenRow, SWT.PUSH);
        compileButton.setText("Compile");
        compileButton.setToolTipText("mvn test-compile (main and test sources)");
        compileButton.addListener(SWT.Selection, event -> runManualBuild(BuildRequest.Goal.TEST_COMPILE));
        testButton = new Button(mavenRow, SWT.PUSH);
        testButton.setText("Test");
        testButton.setToolTipText("mvn test");
        testButton.addListener(SWT.Selection, event -> runManualBuild(BuildRequest.Goal.TEST));
        fixButton = new Button(mavenRow, SWT.PUSH);
        fixButton.setText("Fix with Claude");
        fixButton.setToolTipText("Send the last build's errors to Claude (edit with approval); Claude re-runs the build to verify");
        fixButton.setEnabled(false);
        fixButton.addListener(SWT.Selection, event -> fixWithClaude());
        */

        Composite projectActions = new Composite(parent, SWT.NONE);
        projectActions.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        projectActions.setLayout(new GridLayout(3, false));
        Button browseProject = new Button(projectActions, SWT.PUSH);
        browseProject.setText("Browse workspace folder");
        browseProject.addListener(SWT.Selection, event -> browseProject());
        new Label(projectActions, SWT.NONE).setText("   Mode");
        modeCombo = new Combo(projectActions, SWT.READ_ONLY | SWT.DROP_DOWN);
        modeCombo.setItems("Read-only (plan)", "Edit with approval");
        modeCombo.select(MODE_READ_ONLY);
        modeCombo.addListener(SWT.Selection, event -> history.append("\n[Mode] " + modeCombo.getText()
                + (isEditMode() ? " - every Edit/Write opens a diff for your approval." : " - Claude cannot change files.") + "\n"));

        Composite contextRow = new Composite(parent, SWT.NONE);
        contextRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout contextLayout = new GridLayout(2, false);
        contextLayout.marginHeight = 0;
        contextRow.setLayout(contextLayout);
        includeContextCheck = new Button(contextRow, SWT.CHECK);
        includeContextCheck.setText("Include editor context:");
        includeContextCheck.setSelection(true);
        includeContextCheck.setToolTipText("Send the active editor's file, cursor line, Java element and selected code with your question");
        includeContextCheck.addListener(SWT.Selection, event -> refreshContextLabel());
        contextLabel = new Label(contextRow, SWT.NONE);
        contextLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        history = new Text(parent, SWT.MULTI | SWT.BORDER | SWT.WRAP | SWT.V_SCROLL | SWT.READ_ONLY);
        GridData historyLayout = new GridData(SWT.FILL, SWT.FILL, true, true);
        historyLayout.heightHint = 230;
        history.setLayoutData(historyLayout);
        history.setText("Goms Claude Code Chat " + VERSION + "\nChoose a workspace folder, pick a mode, then ask a question.\n");

        promptField = new Text(parent, SWT.MULTI | SWT.BORDER | SWT.WRAP | SWT.V_SCROLL);
        GridData promptLayout = new GridData(SWT.FILL, SWT.BOTTOM, true, false);
        promptLayout.heightHint = 72;
        promptField.setLayoutData(promptLayout);
        promptField.setMessage("Ask Claude about files in the selected workspace...");

        Composite actions = new Composite(parent, SWT.NONE);
        actions.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        actions.setLayout(new GridLayout(5, false));
        sendButton = new Button(actions, SWT.PUSH);
        sendButton.setText("Send");
        sendButton.addListener(SWT.Selection, event -> send());
        cancelButton = new Button(actions, SWT.PUSH);
        cancelButton.setText("Cancel");
        cancelButton.setEnabled(false);
        cancelButton.addListener(SWT.Selection, event -> cancelRun("Run cancelled by the user."));
        newChatButton = new Button(actions, SWT.PUSH);
        newChatButton.setText("New chat");
        newChatButton.addListener(SWT.Selection, event -> newChat());
        revertButton = new Button(actions, SWT.PUSH);
        revertButton.setText("Revert last change");
        revertButton.setEnabled(false);
        revertButton.addListener(SWT.Selection, event -> revertLastChange());
        status = new Label(actions, SWT.NONE);
        status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        status.setText("Ready");

        useCurrentProject(false);
        installEditorTracking();
    }

    // ------------------------------------------------------------------ V1.1 editor context

    private void installEditorTracking() {
        IWorkbenchWindow window = getSite().getWorkbenchWindow();
        selectionListener = (part, selection) -> { if (part != this) refreshContextLabel(); };
        window.getSelectionService().addPostSelectionListener(selectionListener);
        partListener = new IPartListener2() {
            @Override public void partActivated(IWorkbenchPartReference ref) { refreshContextLabel(); }
            @Override public void partClosed(IWorkbenchPartReference ref) { refreshContextLabel(); }
            @Override public void partInputChanged(IWorkbenchPartReference ref) { refreshContextLabel(); }
        };
        getSite().getPage().addPartListener(partListener);
        refreshContextLabel();
    }

    /** Context to send now: the pinned one (from "Ask Claude about Selection") or the live editor state. */
    private EditorContext currentContext() {
        if (includeContextCheck == null || !includeContextCheck.getSelection()) return null;
        return pinnedContext != null ? pinnedContext : EditorContextCapture.capture(getSite().getWorkbenchWindow());
    }

    private void refreshContextLabel() {
        if (disposed || contextLabel == null || contextLabel.isDisposed()) return;
        String text;
        if (!includeContextCheck.getSelection()) {
            text = "(off)";
        } else {
            EditorContext c = currentContext();
            Path root = rootOrNull();
            text = c == null ? "no text editor open" : (pinnedContext != null ? "[pinned] " : "") + EditorContextFormatter.label(c, root);
        }
        contextLabel.setText(text);
        contextLabel.setToolTipText(text);
        contextLabel.getParent().layout(true, true);
    }

    private Path rootOrNull() {
        try {
            return WorkspaceRootValidator.validate(projectField.getText());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Called by {@link AskClaudeHandler}: keeps this selection as context for the next question. */
    public void pinContext(EditorContext context) {
        if (disposed || context == null) {
            setFocus();
            return;
        }
        pinnedContext = context;
        includeContextCheck.setSelection(true);
        refreshContextLabel();
        if (!running) setStatus("Selection attached - type your question");
        promptField.setFocus();
    }

    private boolean isEditMode() {
        return modeCombo != null && !modeCombo.isDisposed() && modeCombo.getSelectionIndex() == MODE_EDIT;
    }

    private void useCurrentProject(boolean showDialog) {
        Path selected = resolver.resolve();
        if (selected == null) {
            setStatus("Choose workspace folder");
            if (showDialog && MessageDialog.openQuestion(getSite().getShell(), "No Project Selected",
                    "No Eclipse project is selected.\n\n"
                    + "Choose any existing workspace folder, including a parent folder containing multiple projects.\n\n"
                    + "Open the workspace folder browser now?")) browseProject();
            return;
        }
        projectField.setText(selected.toString());
        setStatus("Selected: " + selected.getFileName());
    }

    private void browseProject() {
        DirectoryDialog dialog = new DirectoryDialog(getSite().getShell());
        dialog.setText("Select Claude Code workspace root (any existing folder)");
        String value = dialog.open();
        if (value != null) {
            projectField.setText(value);
            setStatus("Workspace: " + Path.of(value).getFileName());
        }
    }

    private void browseExecutable() {
        FileDialog dialog = new FileDialog(getSite().getShell(), SWT.OPEN);
        dialog.setText("Choose Claude Code executable (native claude.exe recommended)");
        String value = dialog.open();
        if (value != null) executableField.setText(value);
    }

    // ------------------------------------------------------------------ run

    private void send() {
        if (running) return;
        String message = promptField.getText().strip();
        if (message.isBlank()) return;
        sendMessage(message, message, true);
    }

    /**
     * @param message      text sent to Claude
     * @param shown        text shown in the history
     * @param allowContext attach the editor context (if the checkbox is on)
     */
    private void sendMessage(String message, String shown, boolean allowContext) {
        Path root;
        try {
            root = WorkspaceRootValidator.validate(projectField.getText());
        } catch (RuntimeException e) {
            showError(e.getMessage());
            return;
        }
        if (!root.equals(sessionRoot)) resetSession(root);

        boolean edit = isEditMode();
        ClaudeCliService.EditMode mode = null;
        if (edit) {
            try {
                mode = ensureEditInfrastructure();
                broker.beginRun(root);
            } catch (IOException | RuntimeException | LinkageError e) {
                showError("Could not start the IDE approval server, so edit mode is unavailable: " + e);
                return;
            }
            changeMonitor = new WorkspaceSync.UnapprovedChangeMonitor(root, broker::approvedKeysThisRun);
            changeMonitor.start();
        }

        EditorContext context = allowContext ? currentContext() : null;
        pinnedContext = null;
        String prompt = EditorContextFormatter.compose(message, context, root);
        EditorContext editorNow = context != null ? context : EditorContextCapture.capture(getSite().getWorkbenchWindow());
        runRoot = root;
        runEditorFile = editorNow == null ? null : editorNow.file();

        history.append("\nYOU" + (edit ? " (edit with approval)" : "")
                + (context != null ? "  [context: " + EditorContextFormatter.label(context, root) + "]" : "")
                + "\n" + shown + "\n");
        promptField.setText("");
        refreshContextLabel();
        setRunning(true);
        setStatus(edit ? "Claude is working - changes need your approval" : "Claude is analyzing...");

        final Path runRoot = root;
        try {
            cli.ask(executableField.getText().strip(), root, prompt, sessionId, hasCompletedTurn, mode,
                new ClaudeCliService.Listener() {
                    @Override public void onSuccess(String response) {
                        ui(() -> {
                            hasCompletedTurn = true;
                            history.append("\nCLAUDE\n" + response + "\n");
                            finishRun(edit, runRoot, "Ready - session retained");
                        });
                    }
                    @Override public void onFailure(String error) {
                        ui(() -> { history.append("\nERROR\n" + error + "\n"); finishRun(edit, runRoot, "Error"); });
                    }
                    @Override public void onCancelled() {
                        ui(() -> { history.append("\n[Cancelled]\n"); finishRun(edit, runRoot, "Cancelled"); });
                    }
                });
        } catch (RuntimeException e) {
            history.append("\nERROR\n" + e.getMessage() + "\n");
            finishRun(edit, runRoot, "Error");
        }
    }

    private void finishRun(boolean edit, Path root, String statusText) {
        setRunning(false);
        setStatus(statusText);
        if (!edit) return;
        closeActiveDialog("The Claude run ended before you decided.");
        if (broker != null) broker.endRun();
        WorkspaceSync.UnapprovedChangeMonitor monitor = changeMonitor;
        changeMonitor = null;
        if (monitor != null) {
            WorkspaceSync.reportUnapprovedAfterRefresh(root, monitor, unapproved -> ui(() -> {
                if (unapproved.isEmpty()) return;
                history.append("\n[IDE WARNING] These files changed during the run without your approval "
                        + "(a hook, policy or another tool may have written them). Review them before continuing:\n  "
                        + String.join("\n  ", unapproved) + "\n");
            }));
        }
        updateRevertButton();
    }

    private void cancelRun(String reason) {
        closeActiveDialog(reason);
        if (broker != null) broker.cancelPending(reason);
        cli.cancel();
        buildRunner.cancel();
    }

    private void closeActiveDialog(String reason) {
        ApprovalDialog dialog = activeDialog;
        activeDialog = null;
        if (dialog != null) dialog.cancel(reason);
    }

    // ------------------------------------------------------------------ V1.2 approval wiring

    private ClaudeCliService.EditMode ensureEditInfrastructure() throws IOException {
        if (editMode != null && approvalServer != null && approvalServer.isRunning()) return editMode;
        if (broker == null) broker = new ApprovalBroker(new ViewApprovalUi(), new BrokerEvents(), WorkspaceSync::charsetFor);
        if (stateDir == null) stateDir = Files.createTempDirectory("goms-claude-");
        approvalServer = new McpApprovalServer(broker::decide, this::runBuildForClaude);
        approvalServer.start();
        Path mcpConfig = stateDir.resolve("mcp.json");
        Path settings = stateDir.resolve("force-ask.settings.json");
        approvalServer.writeMcpConfig(mcpConfig);
        McpApprovalServer.writeForceAskSettings(settings);
        editMode = new ClaudeCliService.EditMode(mcpConfig, settings, McpApprovalServer.PERMISSION_TOOL);
        return editMode;
    }

    /** Called on an HTTP worker thread; opens the dialog on the UI thread. */
    private CompletableFuture<ApprovalDecision> requestApprovalOnUi(ApprovalRequest request) {
        CompletableFuture<ApprovalDecision> future = new CompletableFuture<>();
        if (disposed || display == null || display.isDisposed()) {
            future.complete(ApprovalDecision.deny("The IDE view was closed."));
            return future;
        }
        display.asyncExec(() -> {
            if (disposed || history.isDisposed()) {
                future.complete(ApprovalDecision.deny("The IDE view was closed."));
                return;
            }
            history.append("\n[IDE] Review requested: " + request.toolName() + " " + request.displayPath() + "\n");
            setStatus("Waiting for your approval: " + request.displayPath());
            boolean dirty = WorkspaceSync.isDirtyInEditor(request.file());
            ApprovalDialog dialog = new ApprovalDialog(getSite().getShell(), request, dirty, decision -> {
                future.complete(decision);
                ui(() -> { activeDialog = null; if (running) setStatus("Claude is working - changes need your approval"); });
            });
            activeDialog = dialog;
            dialog.open();
        });
        return future;
    }

    private final class ViewApprovalUi implements ApprovalBroker.Ui {
        @Override
        public CompletableFuture<ApprovalDecision> requestApproval(ApprovalRequest request) {
            return requestApprovalOnUi(request);
        }

        /** V1.3: Claude wants to run a Maven build. */
        @Override
        public CompletableFuture<ApprovalDecision> requestToolApproval(String title, String description) {
            CompletableFuture<ApprovalDecision> future = new CompletableFuture<>();
            if (disposed || display == null || display.isDisposed()) {
                future.complete(ApprovalDecision.deny("The IDE view was closed."));
                return future;
            }
            display.asyncExec(() -> {
                if (disposed || history.isDisposed()) {
                    future.complete(ApprovalDecision.deny("The IDE view was closed."));
                    return;
                }
                setStatus("Waiting for your approval: " + description);
                MessageDialog dialog = new MessageDialog(getSite().getShell(), "Claude wants to run " + title, null,
                        description + "\n\nThe build runs your project's code and tests on this machine. "
                        + "Output appears in the Console view (Goms Maven).",
                        MessageDialog.QUESTION, 0, "Run", "Run builds for this request", "Deny");
                int choice = dialog.open();
                future.complete(switch (choice) {
                    case 0 -> ApprovalDecision.allow(ApprovalDecision.Kind.ALLOW, null);
                    case 1 -> ApprovalDecision.allow(ApprovalDecision.Kind.ALLOW_ALL, null);
                    default -> ApprovalDecision.deny("The developer did not allow this build.");
                });
                if (running) setStatus("Claude is working - changes need your approval");
            });
            return future;
        }
    }

    private final class BrokerEvents implements ApprovalBroker.Listener {
        @Override public void onEvent(String message) {
            ui(() -> history.append("[IDE] " + message + "\n"));
        }
        @Override public void onApproved(ChangeRecord record) {
            WorkspaceSync.refreshSoon(record.file(), 1500);   // Claude writes the file right after our answer
            ui(ClaudeChatView.this::updateRevertButton);
        }
    }

    private void revertLastChange() {
        if (running || broker == null) return;
        ChangeRecord rec = broker.lastChange();
        if (rec == null) return;
        if (!rec.revertible()) {
            MessageDialog.openWarning(getSite().getShell(), "Revert last change",
                    "The original content of " + rec.displayPath() + " could not be captured, so it cannot be reverted.");
            broker.removeLastChange();
            updateRevertButton();
            return;
        }
        String what = rec.existedBefore()
                ? "restore " + rec.displayPath() + " to its content before Claude's " + rec.toolName()
                : "delete " + rec.displayPath() + " (created by Claude)";
        if (!MessageDialog.openQuestion(getSite().getShell(), "Revert last change",
                "This will " + what + " (approved at " + TIME.format(rec.approvedAt()) + ").\n"
                + "Any later edits to that file will be lost.\n\nContinue?")) return;
        try {
            if (rec.existedBefore()) Files.write(rec.file(), rec.beforeBytes());
            else Files.deleteIfExists(rec.file());
            broker.removeLastChange();
            WorkspaceSync.refreshSoon(rec.file(), 0);
            history.append("\n[IDE] Reverted " + rec.displayPath() + "\n");
        } catch (IOException e) {
            showError("Revert failed for " + rec.displayPath() + ": " + e.getMessage());
        }
        updateRevertButton();
    }

    private void updateRevertButton() {
        if (revertButton == null || revertButton.isDisposed()) return;
        ChangeRecord last = broker == null ? null : broker.lastChange();
        revertButton.setEnabled(!running && last != null);
        revertButton.setToolTipText(last == null ? null : "Revert " + last.displayPath());
    }

    // ------------------------------------------------------------------ V1.3 Maven build / test

    private void browseMaven() {
        FileDialog dialog = new FileDialog(getSite().getShell(), SWT.OPEN);
        dialog.setText("Choose the Maven launcher (mvn.cmd or mvnw.cmd)");
        String value = dialog.open();
        if (value != null) mavenField.setText(value);
    }

    /** Compile / Test buttons. */
    private void runManualBuild(BuildRequest.Goal goal) {
        if (running) return;
        Path root;
        try {
            root = WorkspaceRootValidator.validate(projectField.getText());
        } catch (RuntimeException e) {
            showError(e.getMessage());
            return;
        }
        EditorContext editor = EditorContextCapture.capture(getSite().getWorkbenchWindow());
        Path module = MavenProjectLocator.defaultModule(root, editor == null ? null : editor.file());
        if (module == null) module = chooseModule(root);
        if (module == null) return;
        Path maven = MavenProjectLocator.findMaven(module, root, mavenField.getText());
        if (maven == null) {
            showError("Maven was not found. Add mvnw to the project, set MAVEN_HOME, or choose mvn.cmd with the '...' button.");
            return;
        }
        BuildRequest request = new BuildRequest(module, goal, null);
        history.append("\n[BUILD] " + request.describe(root) + "\n");
        setRunning(true);
        setStatus("Building: " + request.describe(root));
        BuildConsole.LineWriter console = BuildConsole.start();
        Thread worker = new Thread(() -> {
            BuildResult result;
            try {
                result = buildRunner.run(request, maven, console);
            } finally {
                console.close();
            }
            BuildResult r = result;
            ui(() -> {
                setRunning(false);
                buildFinished(r, root, "[BUILD] ");
            });
        }, "goms-maven-build");
        worker.setDaemon(true);
        worker.start();
    }

    private Path chooseModule(Path root) {
        List<Path> modules = MavenProjectLocator.listPomDirs(root);
        if (modules.isEmpty()) {
            showError(MavenProjectLocator.noMavenProjectMessage(root));
            return null;
        }
        if (modules.size() == 1) return modules.get(0);
        ElementListSelectionDialog dialog = new ElementListSelectionDialog(getSite().getShell(), new LabelProvider() {
            @Override public String getText(Object element) {
                Path p = (Path) element;
                return p.equals(root) ? ". (workspace root)" : root.relativize(p).toString().replace('\\', '/');
            }
        });
        dialog.setTitle("Choose Maven project");
        dialog.setMessage("Several Maven projects were found. Which one should be built?\n(Tip: open a file of the project in the editor to choose it automatically.)");
        dialog.setElements(modules.toArray());
        dialog.setMultipleSelection(false);
        if (dialog.open() != ElementListSelectionDialog.OK || dialog.getFirstResult() == null) return null;
        return (Path) dialog.getFirstResult();
    }

    /** UI thread: show a finished build in history, status bar and Problems view. */
    private void buildFinished(BuildResult result, Path root, String prefix) {
        lastBuild = result;
        history.append(prefix + result.headline() + "\n");
        if (!result.success() && !result.cancelled()) {
            String report = result.report(root, 2_500);
            int start = report.indexOf('\n', report.indexOf("Result:"));
            if (start > 0 && start + 1 < report.length()) history.append(report.substring(start + 1).stripTrailing() + "\n");
            if (!running) history.append("Use 'Fix with Claude' to let Claude fix this (each change and each re-build needs your approval).\n");
        }
        if (!running) setStatus(result.headline());
        BuildMarkers.update(result, null);
        updateFixButton();
    }

    private void updateFixButton() {
        if (fixButton == null || fixButton.isDisposed()) return;
        BuildResult b = lastBuild;
        fixButton.setEnabled(!running && b != null && !b.success() && !b.cancelled() && b.hasFixableIssues());
    }

    /** "Fix with Claude": edit-with-approval run seeded with the last build report. */
    private void fixWithClaude() {
        BuildResult b = lastBuild;
        if (running || b == null || b.success()) return;
        Path root;
        try {
            root = WorkspaceRootValidator.validate(projectField.getText());
        } catch (RuntimeException e) {
            showError(e.getMessage());
            return;
        }
        if (!isEditMode()) {
            modeCombo.select(MODE_EDIT);
            history.append("\n[Mode] Edit with approval - switched automatically to let Claude fix the build.\n");
        }
        sendMessage(BuildFixPrompt.compose(b, root), "Fix the build: " + b.headline(), false);
    }

    /**
     * MCP worker thread: Claude called run_maven (already approved by the developer through the broker).
     * Runs synchronously and returns the report Claude sees.
     */
    private String runBuildForClaude(Map<String, Object> args) {
        Path root = runRoot;
        if (root == null) throw new IllegalStateException("No active Claude run.");
        BuildRequest.Goal goal = BuildRequest.Goal.parse(args.get("goal") == null ? null : String.valueOf(args.get("goal")));
        Object moduleArg = args.get("module");
        Path module;
        if (moduleArg != null && !String.valueOf(moduleArg).isBlank()) {
            module = MavenProjectLocator.resolveModule(root, String.valueOf(moduleArg));
        } else {
            module = MavenProjectLocator.defaultModule(root, runEditorFile);
            if (module == null) {
                List<Path> all = MavenProjectLocator.listPomDirs(root);
                if (all.isEmpty()) throw new IllegalArgumentException(MavenProjectLocator.noMavenProjectMessage(root));
                StringBuilder names = new StringBuilder();
                for (Path p : all) names.append(names.length() > 0 ? ", " : "").append(root.relativize(p).toString().replace('\\', '/'));
                throw new IllegalArgumentException("Several Maven projects exist; pass module, one of: " + names);
            }
        }
        Object test = args.get("test");
        BuildRequest request = new BuildRequest(module, goal, test == null ? null : String.valueOf(test));
        String mavenOverride = syncRead(() -> mavenField.getText());
        Path maven = MavenProjectLocator.findMaven(module, root, mavenOverride);
        if (maven == null) throw new IllegalStateException("Maven was not found on the developer's machine (no mvnw, MAVEN_HOME or mvn on PATH). Ask the developer to configure it.");

        ui(() -> {
            history.append("\n[BUILD by Claude] " + request.describe(root) + "\n");
            setStatus("Building for Claude: " + request.describe(root));
        });
        BuildResult result;
        try (BuildConsole.LineWriter console = BuildConsole.start()) {
            result = buildRunner.run(request, maven, console);
        }
        BuildResult r = result;
        ui(() -> {
            buildFinished(r, root, "[BUILD by Claude] ");
            setStatus("Claude is working - changes need your approval");
        });
        return result.report(root, BuildFixPrompt.MAX_REPORT_CHARS);
    }

    /** Reads a widget value from a non-UI thread. */
    private String syncRead(java.util.function.Supplier<String> reader) {
        if (disposed || display == null || display.isDisposed()) return null;
        String[] holder = new String[1];
        display.syncExec(() -> { if (!disposed) holder[0] = reader.get(); });
        return holder[0];
    }

    // ------------------------------------------------------------------ session / UI helpers

    private void resetSession(Path root) {
        sessionRoot = root;
        sessionId = UUID.randomUUID();
        hasCompletedTurn = false;
        history.setText("Goms Claude Code Chat " + VERSION + "\nWorkspace: " + root + "\n");
    }

    private void newChat() {
        if (running) return;
        sessionRoot = null;
        hasCompletedTurn = false;
        sessionId = UUID.randomUUID();
        history.setText("New session. Previous Claude conversation will not be resumed.\n");
        setStatus("New chat ready");
    }

    private void setRunning(boolean isRunning) {
        running = isRunning;
        sendButton.setEnabled(!isRunning);
        cancelButton.setEnabled(isRunning);
        newChatButton.setEnabled(!isRunning);
        projectField.setEnabled(!isRunning);
        executableField.setEnabled(!isRunning);
        modeCombo.setEnabled(!isRunning);
        compileButton.setEnabled(!isRunning);
        testButton.setEnabled(!isRunning);
        updateFixButton();
        updateRevertButton();
    }

    private void ui(Runnable callback) {
        if (disposed || display == null || display.isDisposed()) return;
        display.asyncExec(() -> { if (!disposed && history != null && !history.isDisposed()) callback.run(); });
    }

    private void setStatus(String message) {
        if (status != null && !status.isDisposed()) {
            status.setText(message);
            status.getParent().layout(true, true);
        }
    }

    private void showError(String message) {
        history.append("\nERROR\n" + message + "\n");
        setStatus("Error");
    }

    @Override public void setFocus() { if (promptField != null && !promptField.isDisposed()) promptField.setFocus(); }

    @Override
    public void dispose() {
        disposed = true;
        try {
            if (selectionListener != null) getSite().getWorkbenchWindow().getSelectionService().removePostSelectionListener(selectionListener);
            if (partListener != null) getSite().getPage().removePartListener(partListener);
        } catch (RuntimeException ignored) { /* workbench shutting down */ }
        cancelRun("The IDE view was closed.");
        WorkspaceSync.UnapprovedChangeMonitor monitor = changeMonitor;
        if (monitor != null) monitor.stop();
        if (approvalServer != null) approvalServer.close();
        if (stateDir != null) {
            for (String name : List.of("mcp.json", "force-ask.settings.json")) {
                try { Files.deleteIfExists(stateDir.resolve(name)); } catch (IOException ignored) { /* temp */ }
            }
            try { Files.deleteIfExists(stateDir); } catch (IOException ignored) { /* temp */ }
        }
        super.dispose();
    }
}
