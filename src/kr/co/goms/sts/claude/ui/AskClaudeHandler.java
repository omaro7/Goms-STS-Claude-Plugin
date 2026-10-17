package kr.co.goms.sts.claude.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.handlers.HandlerUtil;

import kr.co.goms.sts.claude.context.EditorContext;
import kr.co.goms.sts.claude.context.EditorContextCapture;

/** Editor context menu / Ctrl+Alt+K: opens Claude Code Chat with the current selection pinned as context. */
public final class AskClaudeHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindowChecked(event);
        IWorkbenchPage page = window.getActivePage();
        if (page == null) return null;
        EditorContext context = EditorContextCapture.capture(page.getActiveEditor());   // before the view takes focus
        try {
            IViewPart view = page.showView(ClaudeChatView.ID);
            if (view instanceof ClaudeChatView chat) chat.pinContext(context);
        } catch (PartInitException e) {
            throw new ExecutionException("Could not open Claude Code Chat", e);
        }
        return null;
    }
}
