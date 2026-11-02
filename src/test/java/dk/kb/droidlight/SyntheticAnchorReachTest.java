package dk.kb.droidlight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests how far from the end of a file the library looks for the JPEG end marker
 * <code>FF D9</code>: the library's anchor reach. This is <b>not</b> a test of PRONOM
 * identification. The files built here are synthetic: they are not files written by any
 * real software, and a file like that does not belong in <code>pronom-samples</code>, which
 * holds only unmodified real files. They are built at run time, in a temporary folder, from
 * the real base samples, and each file is deleted as soon as its check is done.
 *
 * <p>The JPEG signatures of PRONOM accept the end marker only within a fixed distance of the
 * end of the file: 65,536 bytes for the JFIF formats (fmt/43), and 131,072 bytes for
 * Exif 2.3.x (fmt/1507). Both limits are counted from the file's <i>own last</i>
 * <code>FF D9</code>: everything after it in the base sample is cut off and the given number
 * of zero bytes is put in its place. The counts are 0, 2,999 and 3,001 (just below and just
 * above the default anchor distance of 3,000 bytes, so a signature with a larger reach must
 * be found by its own reach), exactly the limit, and one byte beyond the limit. Up to and
 * including the limit the file must be detected as the base sample's format; one byte beyond
 * it nothing must match, so the result is empty. This is checked for both
 * {@link DroidSignatureVerifier#detect(File)} and
 * {@link DroidSignatureVerifier#detect(InputStream)}.
 */
public class SyntheticAnchorReachTest {

    private static final String SIGNATURE_FILE = "DROID_SignatureFile_V124.xml";
    private static final String JFIF_SAMPLE = "pronom-samples/fmt-43_JPEG_File_Interchange_Format_1.01.jpg";
    private static final String EXIF_SAMPLE =
            "pronom-samples/fmt-1507_Exchangeable_Image_File_Format_Compressed_2.3.x--photoshop-xmp-icc-restart.jpg";

    private static final int JFIF_LIMIT = 65_536;
    private static final int EXIF_LIMIT = 131_072;

    @TempDir
    Path folder;

    @TestFactory
    public List<DynamicTest> jfifAnchorReach() throws Exception {
        return tests(JFIF_SAMPLE, "fmt/43", JFIF_LIMIT);
    }

    @TestFactory
    public List<DynamicTest> exif23xAnchorReach() throws Exception {
        return tests(EXIF_SAMPLE, "fmt/1507", EXIF_LIMIT);
    }

    private List<DynamicTest> tests(String sampleName, String puid, int limit) throws Exception {
        DroidSignatureVerifier verifier = new DroidSignatureVerifier(ProjectPaths.mainResource(SIGNATURE_FILE));
        byte[] sample = Files.readAllBytes(ProjectPaths.testResource(sampleName).toPath());
        int end = endOfLastEndMarker(sample);

        List<DynamicTest> tests = new ArrayList<>();
        for (int count : new int[] {0, 2_999, 3_001, limit, limit + 1}) {
            String name = puid + ", " + count + " zero bytes after the last FF D9"
                    + (count == limit ? " (exactly the limit)" : count == limit + 1 ? " (one beyond the limit)" : "");
            tests.add(dynamicTest(name, () -> {
                File file = folder.resolve(puid.replace('/', '-') + "-" + count + ".jpg").toFile();
                // the base sample cut after its last FF D9; the added bytes are zero
                Files.write(file.toPath(), Arrays.copyOf(sample, end + count));
                try {
                    DetectionResult[] byFile = verifier.detect(file);
                    DetectionResult[] byStream = detectByStream(verifier, file);
                    if (count <= limit) {
                        assertEquals(puid, firstCode(byFile), "detect(File), " + count + " bytes after the last FF D9");
                        assertEquals(puid, firstCode(byStream),
                                "detect(InputStream), " + count + " bytes after the last FF D9");
                    } else {
                        assertEquals(0, byFile.length, count + " bytes after the last FF D9, detect(File) returned "
                                + Arrays.toString(byFile));
                        assertEquals(0, byStream.length, count + " bytes after the last FF D9, "
                                + "detect(InputStream) returned " + Arrays.toString(byStream));
                    }
                } finally {
                    Files.deleteIfExists(file.toPath());
                }
            }));
        }
        return tests;
    }

    /** The offset just after the last <code>FF D9</code> of the sample. */
    private static int endOfLastEndMarker(byte[] sample) {
        for (int i = sample.length - 2; i >= 0; i--) {
            if ((sample[i] & 0xFF) == 0xFF && (sample[i + 1] & 0xFF) == 0xD9) {
                return i + 2;
            }
        }
        assertTrue(false, "the sample has no FF D9");
        return -1;
    }

    private static DetectionResult[] detectByStream(DroidSignatureVerifier verifier, File file) throws Exception {
        try (InputStream in = new FileInputStream(file)) {
            return verifier.detect(in);
        }
    }

    private static String firstCode(DetectionResult[] results) {
        return results.length == 0 ? "no match" : results[0].code;
    }
}
