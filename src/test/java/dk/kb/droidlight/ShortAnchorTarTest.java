package dk.kb.droidlight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

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
 * {@link DroidSignatureVerifier} must report x-fmt/265 as the top result for
 * every one of them, regardless of the first entry's file name length.
 *
 * Resource locations are found by locating the project root from the current
 * working directory (see ProjectPaths.projectRoot()) and reading directly from
 * src/main/resources / src/test/resources there - not via the classpath, which
 * depends on a build having already copied resources into an output folder.
 * This runs unchanged regardless of where the repository is checked out.
 */
public class ShortAnchorTarTest {

    private static final String EXPECTED_PUID = "x-fmt/265";

    private static final int[] NAME_LENGTHS = {1, 14, 52, 53, 60, 99};

    /** One test per archive, named after the length of the first entry's file name. */
    @TestFactory
    public Stream<DynamicTest> tarIsDetectedForEveryFileNameLength() throws Exception {
        File sigFile = ProjectPaths.mainResource("DROID_SignatureFile_V124_experimental.xml");
        File folder = ProjectPaths.testResource("short-anchor-tar");

        DroidSignatureVerifier verifier = new DroidSignatureVerifier(sigFile);

        Stream.Builder<DynamicTest> tests = Stream.builder();
        for (int nameLength : NAME_LENGTHS) {
            File tarFile = new File(folder, "name-len-" + nameLength + ".tar");
            tests.add(DynamicTest.dynamicTest("first entry name length " + nameLength, () -> {
                assertTrue(tarFile.isFile(), "Missing fixture: " + tarFile.getAbsolutePath());

                DetectionResult[] results = verifier.detect(tarFile);
                String actual = results.length == 0 ? "NO MATCH" : results[0].code;

                assertEquals(EXPECTED_PUID, actual,
                        tarFile.getName() + ": expected " + EXPECTED_PUID + " as the top result");
            }));
        }
        return tests.build();
    }
}
