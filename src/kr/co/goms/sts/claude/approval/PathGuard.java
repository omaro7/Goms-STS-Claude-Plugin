package kr.co.goms.sts.claude.approval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/** Resolves a tool's file_path and refuses anything the IDE must never let Claude write. */
public final class PathGuard {

    /** Directory names (any depth below the root) that are never writable. */
    public static final Set<String> PROTECTED_DIRS = Set.of(".git", ".claude", ".settings", ".metadata", ".idea", ".svn");
    public static final long MAX_FILE_BYTES = 2_000_000;

    private PathGuard() { }

    /**
     * @param root    workspace root, already a real path
     * @param rawPath file_path from the tool input (absolute or relative to the root)
     * @return the real target path inside the root
     */
    public static Path resolve(Path root, Object rawPath) throws ApprovalRejection {
        if (rawPath == null || String.valueOf(rawPath).isBlank()) throw new ApprovalRejection("file_path is missing.");
        Path p;
        try {
            p = Path.of(String.valueOf(rawPath));
        } catch (InvalidPathException e) {
            throw new ApprovalRejection("Invalid file_path: " + rawPath);
        }
        if (!p.isAbsolute()) p = root.resolve(p);
        p = p.normalize();

        try {
            // Resolve symlinks on the deepest existing ancestor so a link cannot escape the root.
            Path existing = p;
            while (existing != null && !Files.exists(existing)) existing = existing.getParent();
            if (existing == null) throw new ApprovalRejection("Path has no existing parent folder: " + p);
            Path real = existing.toRealPath().resolve(existing.relativize(p)).normalize();
            if (!real.startsWith(root)) throw new ApprovalRejection("Path is outside the workspace root (" + root + "): " + p);

            for (Path part : root.relativize(real)) {
                if (PROTECTED_DIRS.contains(part.toString().toLowerCase(Locale.ROOT)))
                    throw new ApprovalRejection("Writes under '" + part + "' are blocked by the IDE: " + root.relativize(real));
            }
            if (Files.isDirectory(real)) throw new ApprovalRejection("Path is a directory: " + root.relativize(real));
            if (Files.exists(real) && Files.size(real) > MAX_FILE_BYTES)
                throw new ApprovalRejection("File is too large to review in the IDE (> 2 MB): " + root.relativize(real));
            return real;
        } catch (IOException e) {
            throw new ApprovalRejection("Cannot inspect path " + p + ": " + e.getMessage());
        }
    }
}
