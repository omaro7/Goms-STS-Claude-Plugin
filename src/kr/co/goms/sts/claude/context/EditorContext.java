package kr.co.goms.sts.claude.context;

import java.nio.file.Path;

/**
 * Snapshot of the active editor taken when the user sends a prompt (V1.1). Eclipse-independent.
 *
 * @param file               absolute path of the edited file
 * @param projectName        Eclipse project name, or null for files outside the workspace
 * @param javaElement        Java element at the cursor, e.g. "method Hello.main(String[])", or null
 * @param cursorLine         1-based caret line (start of the selection)
 * @param selectionStartLine 1-based first selected line (0 when nothing is selected)
 * @param selectionEndLine   1-based last selected line (0 when nothing is selected)
 * @param selectedText       selected text from the editor buffer, or null
 * @param dirty              true when the editor has unsaved changes
 * @param language           code fence language hint (file extension), may be empty
 */
public record EditorContext(Path file, String projectName, String javaElement, int cursorLine,
        int selectionStartLine, int selectionEndLine, String selectedText, boolean dirty, String language) {

    public boolean hasSelection() {
        return selectedText != null && !selectedText.isEmpty() && selectionStartLine > 0;
    }

    public int selectedLineCount() {
        return hasSelection() ? selectionEndLine - selectionStartLine + 1 : 0;
    }
}
