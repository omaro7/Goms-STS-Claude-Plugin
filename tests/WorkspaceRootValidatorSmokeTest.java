import java.nio.file.Files;
import java.nio.file.Path;
import kr.co.goms.sts.claude.context.WorkspaceRootValidator;

public final class WorkspaceRootValidatorSmokeTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("goms-sr-automation-");
        try {
            Files.createDirectories(root.resolve("SR-Automation-API"));
            Files.createDirectories(root.resolve("SR-Automation-Core"));
            Files.createDirectories(root.resolve("SR-Automation-Infra"));
            Files.createDirectories(root.resolve("SR-Automation-React"));
            Files.createDirectories(root.resolve("SR-Automation-Worker"));
            // No pom.xml, build.gradle, or build.gradle.kts exists.
            Path actual = WorkspaceRootValidator.validate(root.toString());
            if (!actual.equals(root.toAbsolutePath().normalize())) throw new AssertionError("Unexpected workspace root");
            expectInvalid("");
            expectInvalid("   ");
            expectInvalid(root.resolve("missing-directory").toString());
            System.out.println("ALL WORKSPACE ROOT TESTS PASSED");
        } finally {
            try (var walk = Files.walk(root)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.delete(path); } catch (Exception e) { throw new RuntimeException(e); }
                });
            }
        }
    }
    private static void expectInvalid(String value) {
        try {
            WorkspaceRootValidator.validate(value);
            throw new AssertionError("Expected validation error for: " + value);
        } catch (IllegalArgumentException expected) { /* correct */ }
    }
}
