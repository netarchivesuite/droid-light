package dk.kb.droidlight;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

/**
 * The large-file code paths of {@link DroidSignatureVerifier}, tested with files that are
 * built at run time from a small real sample and deleted again afterwards. Nothing large is
 * committed. Tagged <code>large</code>, so the class can be left out with
 * <code>-DexcludedGroups=large</code> (or run on its own with <code>-Dgroups=large</code>).
 *
 * <h2>What is being tested</h2>
 * A <b>file</b> of up to 100,000,000 bytes is read completely; a larger file is examined
 * through a window of 20,000,000 bytes at the start and one at the end. A <b>stream</b> is
 * read through the same two windows when it is longer than 20,000,000 bytes. Up to 40,000,000
 * bytes the two windows together cover the whole stream; a longer stream is examined only at
 * both ends, so a pattern in the middle of it is not seen. The same pattern in a file of up
 * to 100,000,000 bytes is. All sizes here are in decimal
 * megabytes (1 MB = 1,000,000 bytes) because the limits in the verifier are.
 *
 * <h2>The files</h2>
 * <ul>
 * <li><b>JPEG</b>: the base sample of fmt/43 with comment segments (<code>FF FE FF FF</code>
 *     followed by 65,533 bytes) inserted directly after the JFIF header, until the file is at
 *     least 19, 21, 45 and 105 MB. The result is checked to be a valid JPEG: the segment
 *     structure is walked, the original bytes are all still there, and the image decodes to
 *     the same size as the sample.</li>
 * <li><b>MP4</b>: the <code>ftyp</code> and <code>moov</code> boxes of a real file (the
 *     fmt/199 sample with <code>moov</code> at the end) around <code>mdat</code> boxes filled
 *     with zero bytes. The result has the box layout of a real file but is not playable, since
 *     the sample table in <code>moov</code> no longer points at real media data. The fmt/199
 *     signature needs <code>ftyp</code> with a known brand at the start and the text
 *     <code>moov</code> anywhere after it, which is why the position of <code>moov</code>
 *     decides the result.</li>
 * </ul>
 *
 * <h2>Expected results</h2>
 * Both {@link DroidSignatureVerifier#detect(File)} and
 * {@link DroidSignatureVerifier#detect(InputStream)} must return fmt/43 (JPEG) or fmt/199
 * (MP4) as the first result for every file, except the 60 MB MP4 with <code>moov</code> in
 * the middle: there {@code detect(File)} returns fmt/199 and {@code detect(InputStream)}
 * does not return fmt/199 at all.
 */
@Tag("large")
public class LargeFileDetectionTest {

    private static final String SIGNATURE_FILE = "DROID_SignatureFile_V124_experimental.xml";
    private static final String JPEG_SAMPLE = "pronom-samples/fmt-43_JPEG_File_Interchange_Format_1.01.jpg";
    private static final String MP4_SAMPLE = "pronom-samples/fmt-199_MPEG-4_Media_File--moov-at-end.mp4";

    private static final long MB = 1_000_000L;
    private static final int COM_PAYLOAD_BYTES = 65_533;
    /** Marker (2 bytes) + length field (2 bytes, value 65,535) + payload. */
    private static final int COM_SEGMENT_BYTES = 2 + 2 + COM_PAYLOAD_BYTES;

    /** JPEG files of about 19, 21, 45 and 105 MB. */
    @TestFactory
    public Stream<DynamicTest> jpegWithCommentSegments() throws Exception {
        DroidSignatureVerifier verifier = new DroidSignatureVerifier(ProjectPaths.mainResource(SIGNATURE_FILE));
        return Stream.of(19L, 21L, 45L, 105L).map(mb -> DynamicTest.dynamicTest(
                "JPEG, " + mb + " MB of comment segments after the JFIF header",
                () -> withTempFolder(folder -> checkJpeg(verifier, folder, mb * MB))));
    }

    /** MP4 files with <code>moov</code> at the end, at the start, at 15 MB and in the middle. */
    @TestFactory
    public Stream<DynamicTest> mp4WithPaddedMdat() throws Exception {
        DroidSignatureVerifier verifier = new DroidSignatureVerifier(ProjectPaths.mainResource(SIGNATURE_FILE));
        List<Mp4Case> cases = Arrays.asList(
                new Mp4Case("45 MB, moov at the end", 45 * MB, Mp4Case.END, true),
                new Mp4Case("45 MB, moov at the start", 45 * MB, Mp4Case.START, true),
                new Mp4Case("45 MB, moov at 15 MB", 45 * MB, 15 * MB, true),
                new Mp4Case("60 MB, moov in the middle (at 30 MB): the file finds it, the stream does not",
                        60 * MB, 30 * MB, false),
                new Mp4Case("105 MB, moov at the end", 105 * MB, Mp4Case.END, true));
        return cases.stream().map(c -> DynamicTest.dynamicTest("MP4, " + c.name,
                () -> withTempFolder(folder -> checkMp4(verifier, folder, c))));
    }

    // ------------------------------------------------------------------ JPEG

    private static void checkJpeg(DroidSignatureVerifier verifier, Path folder, long minimumBytes) throws Exception {
        byte[] sample = Files.readAllBytes(ProjectPaths.testResource(JPEG_SAMPLE).toPath());
        int insertAt = endOfJfifHeader(sample);
        long segments = (minimumBytes - sample.length + COM_SEGMENT_BYTES - 1) / COM_SEGMENT_BYTES;

        File file = folder.resolve("large.jpg").toFile();
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file.toPath()), 1 << 20)) {
            out.write(sample, 0, insertAt);
            byte[] comment = new byte[COM_SEGMENT_BYTES];
            comment[0] = (byte) 0xFF;
            comment[1] = (byte) 0xFE;
            comment[2] = (byte) 0xFF;
            comment[3] = (byte) 0xFF;
            for (long i = 0; i < segments; i++) {
                out.write(comment);
            }
            out.write(sample, insertAt, sample.length - insertAt);
        }
        assertEquals(sample.length + segments * COM_SEGMENT_BYTES, file.length());
        assertTrue(file.length() >= minimumBytes, "the file is smaller than the " + minimumBytes + " bytes asked for");

        assertValidJpeg(file, sample, insertAt, segments);
        assertDetected(verifier, file, "fmt/43", true);
    }

    /** Offset just after the JFIF header segment: SOI (2 bytes), then APP0 with its length field. */
    private static int endOfJfifHeader(byte[] jpeg) {
        assertEquals((byte) 0xFF, jpeg[0]);
        assertEquals((byte) 0xD8, jpeg[1]);
        assertEquals((byte) 0xFF, jpeg[2]);
        assertEquals((byte) 0xE0, jpeg[3], "the sample must start with a JFIF (APP0) segment");
        assertEquals("JFIF", new String(jpeg, 6, 4, StandardCharsets.US_ASCII));
        return 4 + (((jpeg[4] & 0xFF) << 8) | (jpeg[5] & 0xFF));
    }

    /**
     * The inserted segments are well formed and nothing else changed: the bytes before and
     * after them are those of the sample, and the image still decodes to the sample's size.
     */
    private static void assertValidJpeg(File file, byte[] sample, int insertAt, long segments) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            byte[] head = new byte[insertAt];
            raf.readFully(head);
            assertArrayEquals(Arrays.copyOf(sample, insertAt), head, "bytes before the comment segments changed");
            byte[] header = new byte[4];
            long position = insertAt;
            for (long i = 0; i < segments; i++) {
                raf.seek(position);
                raf.readFully(header);
                assertEquals(0xFF, header[0] & 0xFF, "comment segment " + i + ": marker");
                assertEquals(0xFE, header[1] & 0xFF, "comment segment " + i + ": marker");
                int length = ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
                assertEquals(COM_PAYLOAD_BYTES + 2, length, "comment segment " + i + ": length field");
                position += 2 + length;
            }
            byte[] rest = new byte[sample.length - insertAt];
            raf.seek(position);
            raf.readFully(rest);
            assertEquals(file.length(), position + rest.length, "unexpected bytes at the end of the file");
            assertArrayEquals(Arrays.copyOfRange(sample, insertAt, sample.length), rest,
                    "bytes after the comment segments changed");
            assertEquals((byte) 0xD9, rest[rest.length - 1]);
            assertEquals((byte) 0xFF, rest[rest.length - 2]);
        }
        BufferedImage original = ImageIO.read(new java.io.ByteArrayInputStream(sample));
        BufferedImage decoded = ImageIO.read(file);
        assertNotNull(original, "the sample does not decode");
        assertNotNull(decoded, "the large file does not decode as a JPEG");
        assertEquals(original.getWidth(), decoded.getWidth());
        assertEquals(original.getHeight(), decoded.getHeight());
    }

    // ------------------------------------------------------------------- MP4

    private static void checkMp4(DroidSignatureVerifier verifier, Path folder, Mp4Case c) throws Exception {
        byte[] sample = Files.readAllBytes(ProjectPaths.testResource(MP4_SAMPLE).toPath());
        byte[] ftyp = topLevelBox(sample, "ftyp");
        byte[] moov = topLevelBox(sample, "moov");

        long moovOffset;
        if (c.moovOffset == Mp4Case.START) {
            moovOffset = ftyp.length;
        } else if (c.moovOffset == Mp4Case.END) {
            moovOffset = c.totalBytes - moov.length;
        } else {
            moovOffset = c.moovOffset;
        }

        File file = folder.resolve("large.mp4").toFile();
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file.toPath()), 1 << 20)) {
            out.write(ftyp);
            writeMdat(out, moovOffset - ftyp.length);            // empty when moov directly follows ftyp
            out.write(moov);
            writeMdat(out, c.totalBytes - moovOffset - moov.length); // empty when moov is last
        }
        assertEquals(c.totalBytes, file.length());
        assertBoxLayout(file, moovOffset, moov.length);
        assertDetected(verifier, file, "fmt/199", c.streamFindsIt);
    }

    /** One complete top-level box of the given type, including its 8 byte header. */
    private static byte[] topLevelBox(byte[] mp4, String type) {
        int position = 0;
        while (position + 8 <= mp4.length) {
            long size = readUnsignedInt(mp4, position);
            String boxType = new String(mp4, position + 4, 4, StandardCharsets.ISO_8859_1);
            assertTrue(size >= 8 && position + size <= mp4.length, "box " + boxType + " has an unusable size");
            if (boxType.equals(type)) {
                return Arrays.copyOfRange(mp4, position, position + (int) size);
            }
            position += (int) size;
        }
        throw new AssertionError("no " + type + " box in the sample");
    }

    /** An <code>mdat</code> box of exactly the given size in bytes (header included); nothing if 0. */
    private static void writeMdat(OutputStream out, long boxBytes) throws IOException {
        if (boxBytes == 0) {
            return;
        }
        assertTrue(boxBytes >= 8 && boxBytes < (1L << 32), "mdat box size out of range: " + boxBytes);
        out.write(new byte[] {(byte) (boxBytes >> 24), (byte) (boxBytes >> 16), (byte) (boxBytes >> 8),
                (byte) boxBytes, 'm', 'd', 'a', 't'});
        byte[] zeros = new byte[1 << 20];
        long remaining = boxBytes - 8;
        while (remaining > 0) {
            int n = (int) Math.min(zeros.length, remaining);
            out.write(zeros, 0, n);
            remaining -= n;
        }
    }

    /** Walks the top-level boxes: they must tile the file exactly, and moov must be where it should be. */
    private static void assertBoxLayout(File file, long expectedMoovOffset, int expectedMoovSize) throws IOException {
        List<String> types = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            byte[] header = new byte[8];
            long position = 0;
            while (position < file.length()) {
                raf.seek(position);
                raf.readFully(header);
                long size = readUnsignedInt(header, 0);
                String type = new String(header, 4, 4, StandardCharsets.ISO_8859_1);
                assertTrue(size >= 8 && position + size <= file.length(), type + " box at " + position + " overruns the file");
                if (type.equals("moov")) {
                    assertEquals(expectedMoovOffset, position, "moov offset");
                    assertEquals(expectedMoovSize, size, "moov size");
                }
                types.add(type);
                position += size;
            }
            assertEquals(file.length(), position, "the boxes do not end where the file ends");
        }
        assertEquals("ftyp", types.get(0));
        assertEquals(1, types.stream().filter(t -> t.equals("moov")).count(), "boxes: " + types);
    }

    private static long readUnsignedInt(byte[] b, int offset) {
        return ((b[offset] & 0xFFL) << 24) | ((b[offset + 1] & 0xFFL) << 16) | ((b[offset + 2] & 0xFFL) << 8)
                | (b[offset + 3] & 0xFFL);
    }

    // ---------------------------------------------------------------- shared

    /**
     * detect(File) must return the format as first result. detect(InputStream), over a plain
     * FileInputStream (no mark/reset), must do the same if <code>streamFindsIt</code>, and
     * must not return the format at all otherwise.
     */
    private static void assertDetected(DroidSignatureVerifier verifier, File file, String puid, boolean streamFindsIt)
            throws Exception {
        DetectionResult[] byFile = verifier.detect(file);
        assertEquals(puid, firstCode(byFile), file.length() + " bytes, detect(File) returned " + Arrays.toString(byFile));

        DetectionResult[] byStream;
        try (InputStream in = new FileInputStream(file)) {
            byStream = verifier.detect(in);
        }
        if (streamFindsIt) {
            assertEquals(puid, firstCode(byStream),
                    file.length() + " bytes, detect(InputStream) returned " + Arrays.toString(byStream));
        } else {
            List<String> codes = Arrays.stream(byStream).map(r -> r.code).collect(Collectors.toList());
            assertFalse(codes.contains(puid), file.length() + " bytes, detect(InputStream) was expected not to find "
                    + puid + " but returned " + Arrays.toString(byStream));
        }
    }

    private static String firstCode(DetectionResult[] results) {
        return results.length == 0 ? "no match" : results[0].code;
    }

    private interface FolderAction {
        void run(Path folder) throws Exception;
    }

    /** Runs the action with a fresh temporary folder and deletes it, and everything built in it, afterwards. */
    private static void withTempFolder(FolderAction action) throws Exception {
        Path folder = Files.createTempDirectory("droid-light-large-");
        try {
            action.run(folder);
        } finally {
            try (Stream<Path> walk = Files.walk(folder)) {
                for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).collect(Collectors.toList())) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    /** One MP4 to build: total size, where moov goes, and whether a stream is expected to find it. */
    private static final class Mp4Case {
        static final long START = -1;
        static final long END = -2;

        final String name;
        final long totalBytes;
        final long moovOffset;
        final boolean streamFindsIt;

        Mp4Case(String name, long totalBytes, long moovOffset, boolean streamFindsIt) {
            this.name = name;
            this.totalBytes = totalBytes;
            this.moovOffset = moovOffset;
            this.streamFindsIt = streamFindsIt;
        }
    }
}
