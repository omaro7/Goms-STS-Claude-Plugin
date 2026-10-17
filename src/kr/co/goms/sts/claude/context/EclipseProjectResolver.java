package kr.co.goms.sts.claude.context;

import java.nio.file.Path;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

/** Finds the selected Eclipse project or the active editor's project. */
public final class EclipseProjectResolver {

    public Path resolve() {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null) return null;

        ISelection selection = window.getSelectionService().getSelection();
        if (selection instanceof IStructuredSelection structured && !structured.isEmpty()) {
            IProject project = selectedProject(structured.getFirstElement());
            Path path = toPath(project);
            if (path != null) return path;
        }

        IWorkbenchPage page = window.getActivePage();
        if (page == null) return null;
        IEditorPart editor = page.getActiveEditor();
        if (editor == null) return null;
        if (editor.getEditorInput() instanceof IFileEditorInput fileInput) return toPath(fileInput.getFile().getProject());
        IFile file = editor.getEditorInput().getAdapter(IFile.class);
        return file == null ? null : toPath(file.getProject());
    }

    private IProject selectedProject(Object item) {
        if (item instanceof IJavaElement javaElement) return javaElement.getJavaProject().getProject();
        IResource resource = item instanceof IResource ? (IResource) item : null;
        if (resource == null && item instanceof IAdaptable adaptable) resource = adaptable.getAdapter(IResource.class);
        return resource == null ? null : resource.getProject();
    }

    private Path toPath(IProject project) {
        if (project == null || !project.isAccessible() || project.getLocation() == null) return null;
        return project.getLocation().toFile().toPath().toAbsolutePath().normalize();
    }
}
