package dk.kb.droidlight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JPEG files with more data after the end marker than the signature allows must not be
 * detected. These files do not belong in <code>pronom-samples</code>, which holds only files
 * that are detected as the format in their name; they are built here, at run time, from the
 * committed samples.
 *
 * <p>The JPEG signatures of PRONOM accept the end marker <code>FF D9</code> only within a
 * fixed distance of the end of the file: 65,536 bytes for the JFIF formats (fmt/43), and
 * 131,072 bytes for Exif 2.3.x (fmt/1507). Both limits are measured here from the file's
 * <i>own last</i> <code>FF D9</code>: everything after it is cut off and the given number of
 * zero bytes is put in its place. For each limit the file with exactly that many bytes is
 * detected as a control, and the file with one byte more is not detected at all, neither by
 * {@link DroidSignatureVerifier#detect(File)} nor by {@link DroidSignatureVerifier#detect(InputStream)}.
 * "Not detected" means an empty result, so no other JPEG format matches either.
 */
public class TrailingDataBeyondLimitTest {

    private static final String SIGNATURE_FILE = "DROID_SignatureFile_V124.xml";
    private static final String JFIF_SAMPLE = "pronom-samples/fmt-43_JPEG_File_Interchange_Format_1.01.jpg";
    private static final String EXIF_SAMPLE =
            "pronom-samples/fmt-1507_Exchangeable_Image_File_Format_Compressed_2.3.x--photoshop-xmp-icc-restart.jpg";

    @TempDir
    Path folder;

    @Test
    public void jfifOneByteBeyondTheLimitIsNotDetected() throws Exception {
        assertLimit(JFIF_SAMPLE, "fmt/43", 65_536);
    }

    @Test
    public void exif23xOneByteBeyondTheLimitIsNotDetected() throws Exception {
        assertLimit(EXIF_SAMPLE, "fmt/1507", 131_072);
    }

    private void assertLimit(String sampleName, String puid, int limit) throws Exception {
        DroidSignatureVerifier verifier = new DroidSignatureVerifier(ProjectPaths.mainResource(SIGNATURE_FILE));
        byte[] sample = Files.readAllBytes(ProjectPaths.testResource(sampleName).toPath());

        File atLimit = withTrailingBytes(sample, limit, "at-limit.jpg");
        assertEquals(puid, firstCode(verifier.detect(atLimit)), "control: " + limit + " bytes after the last FF D9");
        assertEquals(puid, firstCode(detectByStream(verifier, atLimit)), "control by stream: " + limit + " bytes");

        File beyond = withTrailingBytes(sample, limit + 1, "beyond-limit.jpg");
        DetectionResult[] byFile = verifier.detect(beyond);
        DetectionResult[] byStream = detectByStream(verifier, beyond);
        assertEquals(0, byFile.length, (limit + 1) + " bytes after the last FF D9, detect(File) returned "
                + Arrays.toString(byFile));
        assertEquals(0, byStream.length, (limit + 1) + " bytes after the last FF D9, detect(InputStream) returned "
                + Arrays.toString(byStream));
    }

    /** The sample cut after its last FF D9, followed by exactly <code>count</code> zero bytes. */
    private File withTrailingBytes(byte[] sample, int count, String name) throws Exception {
        int end = -1;
        for (int i = sample.length - 2; i >= 0; i--) {
            if ((sample[i] & 0xFF) == 0xFF && (sample[i + 1] & 0xFF) == 0xD9) {
                end = i + 2;
                break;
            }
        }
        assertTrue(end > 0, "the sample has no FF D9");
        byte[] data = Arrays.copyOf(sample, end + count); // the added bytes are zero
        File file = folder.resolve(name).toFile();
        Files.write(file.toPath(), data);
        return file;
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
