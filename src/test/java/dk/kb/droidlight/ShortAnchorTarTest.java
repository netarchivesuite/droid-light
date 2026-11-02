package dk.kb.droidlight;

import java.io.File;

/**
 * Regression test for TAR detection (x-fmt/265).
 *
 * <p>The TAR signature's first anchor is the single byte 0x00. A real ustar
 * header's 100-byte file-name field is null-padded, so the number of zero
 * bytes preceding the true anchor position depends on how long the first
 * entry's file name is: a short name leaves a long run of padding zeroes
 * before the real match, a long name leaves almost none.
 *
 * <p>This test scans six genuine archives, each created with the real
 * {@code tar --format=ustar} command (not hand-built bytes), whose single
 * entry's file name is exactly 1, 14, 52, 53, 60 or 99 characters long.
 * Both {@link DroidSignatureVerifier} and {@link DroidSignatureAhoCorasickVerifier}
 * must report x-fmt/265 as the top result for every one of them, regardless
 * of the first entry's file name length.
 *
 * Resource locations are found by locating the project root from the current
 * working directory (see ProjectPaths.projectRoot()) and reading directly from
 * src/main/resources / src/test/resources there - not via the classpath, which
 * depends on a build having already copied resources into an output folder.
 * This runs unchanged regardless of where the repository is checked out.
 *
 * No CLI args - meant to be run directly from an IDE, same style as
 * DroidSignatureVerifier's own main().
 */
public class ShortAnchorTarTest {

    private static final String EXPECTED_PUID = "x-fmt/265";

    public static void main(String[] args) throws Exception {
        File sigFile = ProjectPaths.mainResource("DROID_SignatureFile_V124.xml");
        File folder = ProjectPaths.testResource("short-anchor-tar");

        int[] nameLengths = {1, 14, 52, 53, 60, 99};

        DroidSignatureVerifier verifier1 = new DroidSignatureVerifier(sigFile);
        DroidSignatureAhoCorasickVerifier verifier2 = new DroidSignatureAhoCorasickVerifier(sigFile);

        System.out.println("First-entry name length | DroidSignatureVerifier | DroidSignatureAhoCorasickVerifier");
        System.out.println("-------------------------------------------------------------------------------------");

        int failures = 0;
        for (int nameLength : nameLengths) {
            File tarFile = new File(folder, "name-len-" + nameLength + ".tar");
            if (!tarFile.isFile()) {
                System.out.println(nameLength + " -> MISSING FIXTURE: " + tarFile);
                failures++;
                continue;
            }

            String result1 = topCode(verifier1.detect(tarFile));
            String result2 = topCode(verifier2.detect(tarFile));

            boolean pass1 = EXPECTED_PUID.equals(result1);
            boolean pass2 = EXPECTED_PUID.equals(result2);
            if (!pass1 || !pass2) failures++;

            System.out.printf("%-24d | %-22s | %-22s%n",
                    nameLength,
                    result1 + (pass1 ? " (PASS)" : " (FAIL, expected " + EXPECTED_PUID + ")"),
                    result2 + (pass2 ? " (PASS)" : " (FAIL, expected " + EXPECTED_PUID + ")"));
        }

        System.out.println();
        if (failures == 0) {
            System.out.println("ALL PASS: both verifiers returned " + EXPECTED_PUID + " for every file name length.");
        } else {
            System.out.println(failures + " length(s) FAILED.");
        }
    }

    private static String topCode(DetectionResult[] results) {
        if (results == null || results.length == 0) return "NO MATCH";
        return results[0].code;
    }
}
