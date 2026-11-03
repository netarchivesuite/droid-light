package dk.kb.droidlight;

import java.io.File;

/**
 * Locates files and folders in the project's actual source tree - never a
 * build output or classpath copy, which can be stale, incomplete, or simply
 * not yet created depending on the build tool and whether a build has run
 * since the last change - so tests work unchanged regardless of which folder
 * the repository is checked out into.
 *
 * Resolution walks upward from the current working directory looking for the
 * project root (rather than assuming the working directory already IS the
 * project root): both Maven's surefire plugin and a typical IDE run/debug
 * configuration default the working directory to the project root, but this
 * does not depend on that holding exactly.
 */
final class ProjectPaths {

    private ProjectPaths() {
    }

    /** A file under src/main/resources, e.g. "DROID_SignatureFile_V124_experimental.xml". */
    static File mainResource(String relativePath) {
        return existing(new File(projectRoot(), "src/main/resources/" + relativePath));
    }

    /** A file or folder under src/test/resources, e.g. "pronom-samples". */
    static File testResource(String relativePath) {
        return existing(new File(projectRoot(), "src/test/resources/" + relativePath));
    }

    private static File existing(File f) {
        if (!f.exists()) {
            throw new IllegalStateException("Expected to find " + f.getAbsolutePath()
                    + " but it does not exist. Project root was resolved to " + projectRoot().getAbsolutePath()
                    + " (searched upward from " + System.getProperty("user.dir") + ").");
        }
        return f;
    }

    /** Walks upward from the current working directory until it finds a folder
     *  that looks like the droid-light project root: one containing both
     *  pom.xml and src/main/resources/DROID_SignatureFile_V124_experimental.xml. */
    static File projectRoot() {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        while (dir != null) {
            if (new File(dir, "pom.xml").isFile()
                    && new File(dir, "src/main/resources/DROID_SignatureFile_V124_experimental.xml").isFile()) {
                return dir;
            }
            dir = dir.getParentFile();
        }
        throw new IllegalStateException("Could not locate the droid-light project root (looked for pom.xml "
                + "and src/main/resources/DROID_SignatureFile_V124_experimental.xml in " + System.getProperty("user.dir")
                + " and every parent folder above it).");
    }
}
