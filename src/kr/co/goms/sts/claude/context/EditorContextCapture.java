package kr.co.goms.sts.claude.context;

import java.nio.file.Path;
import java.util.Locale;

import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IInitializer;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IPathEditorInput;
import org.eclipse.ui.IURIEditorInput;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;

/** Reads the active text editor's file, caret, selection and enclosing Java element (UI thread only). */
public final class EditorContextCapture {

    private EditorContextCapture() { }

    /** @return the context of the active (or last active) editor, or null when there is no text editor */
    public static EditorContext capture(IWorkbenchWindow window) {
        if (window == null) return null;
        IWorkbenchPage page = window.getActivePage();
        if (page == null) return null;
        return capture(page.getActiveEditor());
    }

    public static EditorContext capture(IEditorPart editor) {
        if (editor == null) return null;
        IEditorInput input = editor.getEditorInput();
        IFile ifile = input.getAdapter(IFile.class);
        Path file = locationOf(ifile, input);
        if (file == null) return null;

        ISelectionProvider provider = editor.getSite().getSelectionProvider();
        ISelection selection = provider == null ? null : provider.getSelection();
        if (!(selection instanceof ITextSelection ts)) return null;             // not a text editor

        int startLine = Math.max(ts.getStartLine(), 0) + 1;
        int endLine = Math.max(ts.getEndLine(), ts.getStartLine()) + 1;
        String text = ts.getLength() > 0 ? ts.getText() : null;
        boolean hasSelection = text != null && !text.isEmpty();

        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        String element = "java".equals(ext) && ifile != null ? javaElementAt(ifile, ts.getOffset()) : null;

        return new EditorContext(file, ifile == null ? null : ifile.getProject().getName(), element, startLine,
                hasSelection ? startLine : 0, hasSelection ? endLine : 0, hasSelection ? text : null,
                editor.isDirty(), ext);
    }

    private static Path locationOf(IFile ifile, IEditorInput input) {
        if (ifile != null && ifile.getLocation() != null) return ifile.getLocation().toFile().toPath().toAbsolutePath().normalize();
        if (input instanceof IPathEditorInput pathInput) {
            try { return pathInput.getPath().toFile().toPath().toAbsolutePath().normalize(); } catch (RuntimeException ignored) { /* next */ }
        }
        if (input instanceof IURIEditorInput uriInput && "file".equalsIgnoreCase(uriInput.getURI().getScheme())) {
            return Path.of(uriInput.getURI()).toAbsolutePath().normalize();
        }
        return null;
    }

    /** "method Type.name(ParamTypes)", "class Type", "field Type.name" ... or null. */
    static String javaElementAt(IFile file, int offset) {
        try {
            if (!(JavaCore.create(file) instanceof ICompilationUnit cu) || !cu.exists()) return null;
            IJavaElement element = cu.getElementAt(offset);
            if (element instanceof IMethod m) {
                StringBuilder sb = new StringBuilder(m.isConstructor() ? "constructor " : "method ");
                sb.append(qualifiedTypeName(m.getDeclaringType())).append('.').append(m.getElementName()).append('(');
                String[] params = m.getParameterTypes();
                for (int i = 0; i < params.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(Signature.getSignatureSimpleName(params[i]));
                }
                return sb.append(')').toString();
            }
            if (element instanceof IField f) return "field " + qualifiedTypeName(f.getDeclaringType()) + "." + f.getElementName();
            if (element instanceof IInitializer i) return "initializer of " + qualifiedTypeName(i.getDeclaringType());
            if (element instanceof IType t) {
                String kind = t.isInterface() ? "interface " : t.isEnum() ? "enum " : t.isRecord() ? "record " : "class ";
                return kind + qualifiedTypeName(t);
            }
            return null;
        } catch (JavaModelException | RuntimeException e) {
            return null;                                // context is best effort
        }
    }

    /** Outer.Inner style name without the package. */
    private static String qualifiedTypeName(IType type) {
        return type == null ? "?" : type.getTypeQualifiedName('.');
    }
}
