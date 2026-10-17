package kr.co.goms.sts.claude.context;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/** Validates a Claude Code working directory, independent of build tools or project layout. */
public final class WorkspaceRootValidator {

    private WorkspaceRootValidator() { }

    public static Path validate(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Choose a workspace folder first");
        try {
            Path root = Path.of(value.strip()).toAbsolutePath().normalize();
            if (!Files.isDirectory(root)) throw new IllegalArgumentException("Workspace folder does not exist: " + root);
            if (!Files.isReadable(root)) throw new IllegalArgumentException("Workspace folder is not readable: " + root);
            return root;
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("Invalid workspace folder path: " + value, e);
        }
    }
}
