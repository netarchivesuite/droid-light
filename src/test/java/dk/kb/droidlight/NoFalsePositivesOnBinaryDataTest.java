package dk.kb.droidlight;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * The heuristic signatures for CSS (x-fmt/224), JavaScript (x-fmt/423) and JSON (fmt/817) look for
 * a few characters such as <code>{</code>, <code>[</code>, <code>"</code> and <code>:</code>. Those
 * characters also occur by chance in binary data, so this test checks that binary data that is not
 * one of the three formats is never identified as one of them. Two kinds of data are checked, each
 * through {@link DroidSignatureVerifier#detect(File)} and
 * {@link DroidSignatureVerifier#detect(InputStream)}:
 * <ol>
 * <li><b>Random bytes</b> from a generator with a fixed seed, so every run sees the same bytes:
 *     20 files each of {@value #SIZES_TEXT} bytes.</li>
 * <li><b>The payload of real binary files</b>: every sample in <code>src/test/resources/pronom-samples</code>
 *     that is larger than {@value #MIN_SAMPLE_SIZE} bytes and is binary (it contains a control byte that
 *     does not occur in text: 0x00-0x08, 0x0B or 0x0E-0x1F), with its first {@value #CUT} bytes cut off. The
 *     cut removes the format's own magic bytes, so what is left is data such as compressed image or
 *     audio data, headers and tables, with nothing in it that identifies a format.</li>
 * </ol>
 * None of the files may get x-fmt/224, x-fmt/423 or fmt/817 anywhere in the result list.
 * The files are built at run time in a temporary folder and are not stored in the project.
 */
public class NoFalsePositivesOnBinaryDataTest {

    private static final String SIGNATURE_FILE = "DROID_SignatureFile_V124_experimental.xml";
    private static final String SAMPLE_FOLDER = "pronom-samples";

    private static final String SIZES_TEXT = "1,500, 4,000, 64,000 and 1,000,000";
    private static final int[] SIZES = {1_500, 4_000, 64_000, 1_000_000};
    private static final int FILES_PER_SIZE = 20;
    private static final long SEED = 20260211L;

    private static final int MIN_SAMPLE_SIZE = 4_000;
    private static final int CUT = 64;

    /** The PUIDs of the three heuristic formats. */
    private static final List<String> HEURISTIC_PUIDS = Arrays.asList("x-fmt/224", "x-fmt/423", "fmt/817");

    @TempDir
    Path folder;

    @TestFactory
    public List<DynamicTest> randomBytes() throws Exception {
        DroidSignatureVerifier verifier = new DroidSignatureVerifier(ProjectPaths.mainResource(SIGNATURE_FILE));
        Random random = new Random(SEED);
        List<DynamicTest> tests = new ArrayList<>();
        for (int size : SIZES) {
            for (int n = 1; n <= FILES_PER_SIZE; n++) {
                byte[] data = new byte[size];
                random.nextBytes(data); // generated here, in a fixed order, so the bytes never depend on test order
                String name = "random bytes, " + size + " bytes, file " + n;
                tests.add(dynamicTest(name, () -> check(verifier, name, data)));
            }
        }
        return tests;
    }

    @TestFactory
    public List<DynamicTest> binarySamplesWithoutTheirFirstBytes() throws Exception {
        DroidSignatureVerifier verifier = new DroidSignatureVerifier(ProjectPaths.mainResource(SIGNATURE_FILE));
        File[] entries = ProjectPaths.testResource(SAMPLE_FOLDER).listFiles();
        assertTrue(entries != null && entries.length > 0, "no sample files found");
        Arrays.sort(entries, Comparator.comparing(File::getName));

        List<DynamicTest> tests = new ArrayList<>();
        for (File sample : entries) {
            if (!sample.isFile() || sample.getName().equals("MANIFEST.json") || sample.getName().startsWith(".")
                    || sample.length() <= MIN_SAMPLE_SIZE) {
                continue;
            }
            byte[] bytes = Files.readAllBytes(sample.toPath());
            if (!isBinary(bytes)) {
                continue;
            }
            byte[] payload = Arrays.copyOfRange(bytes, CUT, bytes.length);
            String name = sample.getName() + " without its first " + CUT + " bytes";
            tests.add(dynamicTest(name, () -> check(verifier, name, payload)));
        }
        assertFalse(tests.isEmpty(), "no binary samples found");
        return tests;
    }

    /** True if the data has a control byte that text does not have: 0x00-0x08, 0x0B or 0x0E-0x1F. */
    static boolean isBinary(byte[] data) {
        for (byte b : data) {
            int c = b & 0xFF;
            if (c <= 0x08 || c == 0x0B || (c >= 0x0E && c <= 0x1F)) {
                return true;
            }
        }
        return false;
    }

    private void check(DroidSignatureVerifier verifier, String name, byte[] data) throws Exception {
        File file = Files.createTempFile(folder, "data", ".bin").toFile();
        try {
            Files.write(file.toPath(), data);
            assertNoHeuristicFormat(name + ", detect(File)", verifier.detect(file));
            try (InputStream in = new FileInputStream(file)) {
                assertNoHeuristicFormat(name + ", detect(InputStream)", verifier.detect(in));
            }
        } finally {
            Files.deleteIfExists(file.toPath());
        }
    }

    private static void assertNoHeuristicFormat(String what, DetectionResult[] results) {
        for (DetectionResult result : results) {
            assertFalse(HEURISTIC_PUIDS.contains(result.code),
                    what + ": binary data was identified as " + result + "; all results: " + Arrays.toString(results));
        }
    }
}
