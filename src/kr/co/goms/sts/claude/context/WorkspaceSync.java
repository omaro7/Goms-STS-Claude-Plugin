package kr.co.goms.sts.claude.context;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;

import kr.co.goms.sts.claude.approval.ApprovalBroker;

/**
 * Keeps Eclipse in sync with files Claude Code writes on disk: charset lookup, dirty-editor checks,
 * refreshes, and detection of changes that were never approved. Works only for folders that are part of
 * the Eclipse workspace; other folders are skipped silently.
 */
public final class WorkspaceSync {

    private WorkspaceSync() { }

    private static IWorkspaceRoot wsRoot() {
        return ResourcesPlugin.getWorkspace().getRoot();
    }

    public static IFile[] filesFor(Path file) {
        return wsRoot().findFilesForLocationURI(file.toAbsolutePath().normalize().toUri());
    }

    private static IContainer[] containersFor(Path dir) {
        return wsRoot().findContainersForLocationURI(dir.toAbsolutePath().normalize().toUri());
    }

    /** Project/file encoding when the file is in the workspace, else UTF-8. */
    public static Charset charsetFor(Path file) {
        try {
            for (IFile f : filesFor(file)) {
                String cs = f.exists() ? f.getCharset() : f.getParent().getDefaultCharset();
                if (cs != null) return Charset.forName(cs);
            }
            Path parent = file.getParent();
            if (parent != null) {
                for (IContainer c : containersFor(parent)) {
                    String cs = c.getDefaultCharset();
                    if (cs != null) return Charset.forName(cs);
                }
            }
        } catch (CoreException | RuntimeException ignored) {
            // fall through
        }
        return StandardCharsets.UTF_8;
    }

    /** True when an editor holds unsaved changes for this file (they would conflict with Claude's write). */
    public static boolean isDirtyInEditor(Path file) {
        try {
            var manager = FileBuffers.getTextFileBufferManager();
            for (IFile f : filesFor(file)) {
                ITextFileBuffer buffer = manager.getTextFileBuffer(f.getFullPath(), LocationKind.IFILE);
                if (buffer != null && buffer.isDirty()) return true;
            }
            @SuppressWarnings("deprecation")
            IPath location = org.eclipse.core.runtime.Path.fromOSString(file.toAbsolutePath().toString());
            ITextFileBuffer external = manager.getTextFileBuffer(location, LocationKind.LOCATION);
            return external != null && external.isDirty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Refreshes the file (and its folder, for new files) after a short delay, once Claude has written it. */
    public static void refreshSoon(Path file, long delayMs) {
        Path parent = file.getParent();
        WorkspaceJob job = new WorkspaceJob("Refresh " + file.getFileName()) {
            @Override
            public IStatus runInWorkspace(IProgressMonitor monitor) throws CoreException {
                for (IFile f : filesFor(file)) f.refreshLocal(IResource.DEPTH_ZERO, monitor);
                if (parent != null) for (IContainer c : containersFor(parent)) c.refreshLocal(IResource.DEPTH_ONE, monitor);
                return Status.OK_STATUS;
            }
        };
        job.setSystem(true);
        job.schedule(delayMs);
    }

    /** Refreshes the whole workspace root folder, then calls {@code done} (on a worker thread). */
    public static void refreshTree(Path root, Runnable done) {
        WorkspaceJob job = new WorkspaceJob("Refresh after Claude run") {
            @Override
            public IStatus runInWorkspace(IProgressMonitor monitor) throws CoreException {
                for (IContainer c : containersFor(root)) c.refreshLocal(IResource.DEPTH_INFINITE, monitor);
                return Status.OK_STATUS;
            }
        };
        job.setSystem(true);
        job.addJobChangeListener(new JobChangeAdapter() {
            @Override
            public void done(IJobChangeEvent event) {
                done.run();   // resource-change notifications for the refresh are delivered before done()
            }
        });
        job.schedule();
    }

    /**
     * Collects files under the root that changed while a Claude run was active but were never approved
     * (e.g. allowed by a hook or managed policy). Derived resources (build output) are ignored.
     */
    public static final class UnapprovedChangeMonitor implements IResourceChangeListener {
        /** Folders written by builds, never by Claude's approved edits. */
        static final Set<String> BUILD_OUTPUT_DIRS = Set.of("target", "bin", "build", ".gradle", "node_modules");
        private final Path root;
        private final Supplier<Set<String>> approvedKeys;
        private final Set<String> changed = new LinkedHashSet<>();
        private final String rootKey;

        public UnapprovedChangeMonitor(Path root, Supplier<Set<String>> approvedKeys) {
            this.root = root.toAbsolutePath().normalize();
            this.rootKey = ApprovalBroker.key(this.root);
            this.approvedKeys = approvedKeys;
        }

        public void start() {
            ResourcesPlugin.getWorkspace().addResourceChangeListener(this, IResourceChangeEvent.POST_CHANGE);
        }

        /** Stops listening and returns changed-but-unapproved paths relative to the root. */
        public List<String> stop() {
            ResourcesPlugin.getWorkspace().removeResourceChangeListener(this);
            Set<String> approved = approvedKeys.get();
            List<String> result = new ArrayList<>();
            synchronized (changed) {
                for (String k : changed) if (!approved.contains(k)) result.add(k);
            }
            List<String> relative = new ArrayList<>();
            for (String k : result) relative.add(k.length() > rootKey.length() ? k.substring(rootKey.length() + 1) : k);
            return relative;
        }

        @Override
        public void resourceChanged(IResourceChangeEvent event) {
            IResourceDelta delta = event.getDelta();
            if (delta == null) return;
            try {
                delta.accept(d -> {
                    IResource r = d.getResource();
                    if (r.isDerived() || r.isTeamPrivateMember()) return false;
                    if (r.getType() == IResource.FOLDER && BUILD_OUTPUT_DIRS.contains(r.getName())) return false;   // Maven/Gradle output (V1.3 builds)
                    if (r.getType() != IResource.FILE) return true;
                    boolean added = d.getKind() == IResourceDelta.ADDED;
                    boolean content = d.getKind() == IResourceDelta.CHANGED && (d.getFlags() & IResourceDelta.CONTENT) != 0;
                    boolean removed = d.getKind() == IResourceDelta.REMOVED;
                    if ((added || content || removed) && r.getLocation() != null) {
                        String k = ApprovalBroker.key(r.getLocation().toFile().toPath());
                        if (k.startsWith(rootKey + java.io.File.separator)) synchronized (changed) { changed.add(k); }
                    }
                    return false;
                });
            } catch (CoreException ignored) {
                // best effort safety net
            }
        }
    }

    /** Convenience for UI code: run {@code consumer} with the unapproved list after a refresh. */
    public static void reportUnapprovedAfterRefresh(Path root, UnapprovedChangeMonitor monitor, Consumer<List<String>> consumer) {
        refreshTree(root, () -> consumer.accept(monitor.stop()));
    }
}
