package dk.kb.droidlight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Primary detection test for {@link DroidSignatureVerifier}: every sample file in
 * <code>src/test/resources/pronom-samples</code> must be identified as the format
 * its own file name says it is.
 *
 * <h2>The file name rule</h2>
 * A sample file name starts with the PUID of the format it is a sample of, with the
 * <code>/</code> of the PUID written as <code>-</code> (a PUID cannot be used in a file
 * name), followed by an underscore and then anything at all (a readable name and the
 * extension):
 *
 * <pre>
 * fmt-477_Acrobat_PDF_A_-_Portable_Document_Format_2b.pdf   must be detected as  fmt/477
 * x-fmt-88_Microsoft_PowerPoint_Presentation.ppt            must be detected as  x-fmt/88
 * </pre>
 *
 * The text before the first underscore is the prefix; its last hyphen becomes the
 * <code>/</code> of the PUID. Nothing else in the file name is interpreted.
 *
 * <p>The PUID from the prefix must be the <b>first</b> result of
 * {@link DroidSignatureVerifier#detect(File)}.
 *
 * <h2>Adding samples</h2>
 * Put a correctly named file in the folder - this class needs no change. Each file
 * shows up as its own test in the test report, named after the file. A file that does
 * not follow the naming rule fails {@link #everySampleFileNameStartsWithAPuidPrefix()}
 * instead of being skipped silently. <code>MANIFEST.json</code> and hidden files (a
 * name starting with a dot) are not samples and are ignored.
 *
 * <p>The folder holds exactly one sample per format: two files with the same PUID
 * prefix fail {@link #everyFormatHasExactlyOneSample()}. A variant of a format that
 * tests something different does not belong in this folder; it goes in a folder of
 * its own, with the reason written next to it.
 *
 * <h2>Special cases</h2>
 * A few real files are matched by more than one format. PRONOM resolves this with
 * <code>HasPriorityOverFileFormatID</code> entries in the signature file: a format
 * with priority is reported first and the formats it outranks are still reported after it.
 * These files are listed in {@link #SPECIAL_CASES}, each with an explicit expectation
 * and a reason taken from the signature file's priority data. An expectation there must
 * never be changed without changing the reason next to it. There are two kinds:
 * <ul>
 * <li><b>ordered</b>: the results must start with exactly the listed PUIDs, in that order
 *     (the file is a sample of all of them, and the signature file defines their order);</li>
 * <li><b>unordered</b>: all listed PUIDs must be among the results, and no order is
 *     asserted (the signature file defines none).</li>
 * </ul>
 *
 * <h2>Streams</h2>
 * Each file is also detected through {@link DroidSignatureVerifier#detect(InputStream)}
 * using a plain {@link FileInputStream}, which does not support mark/reset, so the
 * verifier reads from the start of the stream. It must return exactly the same list as
 * the file based method.
 *
 * <p>The signature file and the sample folder are found from the project root (see
 * {@link ProjectPaths}), so the test runs unchanged wherever the project is checked out.
 */
public class PronomSamplesDetectionTest {

    private static final String SIGNATURE_FILE = "DROID_SignatureFile_V124.xml";
    private static final String SAMPLE_FOLDER = "pronom-samples";
    private static final String MANIFEST = "MANIFEST.json";

    /**
     * The text before the first underscore: a PUID scheme such as "fmt" or "x-fmt", a
     * hyphen, and the number. Group 1 is the scheme, group 2 the number.
     */
    private static final Pattern PREFIX = Pattern.compile("^([a-z]+(?:-[a-z]+)*)-(\\d+)_.*");

    /**
     * Samples that deviate from "the PUID in the file name is the first result", keyed by
     * file name. Every entry carries the reason, taken from the priority data in
     * DROID_SignatureFile_V124.xml (the numbers are FileFormat IDs, not PUIDs).
     */
    private static final Map<String, SpecialCase> SPECIAL_CASES = new LinkedHashMap<>();
    static {
        SPECIAL_CASES.put("fmt-1355_WARC_1.0.warc", SpecialCase.ordered(
                "fmt/1355 (WARC 1.0) has priority over fmt/289 (WARC), HasPriorityOverFileFormatID 1029; "
                        + "this one file is the sample for both PUIDs, and fmt/289 is still reported after fmt/1355.",
                "fmt/1355", "fmt/289"));

        SPECIAL_CASES.put("fmt-196_Adobe_InDesign_Document_CS.indd", SpecialCase.ordered(
                "fmt/196 (InDesign CS) has priority over x-fmt/450 (InDesign Generic), HasPriorityOverFileFormatID 865; "
                        + "this one file is the sample for both PUIDs, and x-fmt/450 is still reported after fmt/196.",
                "fmt/196", "x-fmt/450"));

        SPECIAL_CASES.put("fmt-1280_NCH_Dictation_Audio_File.mov", SpecialCase.unordered(
                "fmt/1280 and x-fmt/384 (QuickTime) both match this file and neither has priority over the other: "
                        + "fmt/1280 only has priority over fmt/134 and x-fmt/384 over nothing, and fmt/199 and fmt/797, "
                        + "which do outrank x-fmt/384, do not match this file. The order of the two is not defined "
                        + "by the signature file, so it is not asserted.",
                "fmt/1280", "x-fmt/384"));
    }

    /** The sample folder must exist and contain at least one sample. */
    @Test
    public void sampleFolderIsNotEmpty() {
        File folder = sampleFolder();
        System.out.println("Sample folder: " + folder.getAbsolutePath());
        assertTrue(folder.isDirectory(), "Not a folder: " + folder.getAbsolutePath());
        assertFalse(sampleFiles().isEmpty(), "No sample files found in " + folder.getAbsolutePath());
    }

    /** Every sample must carry a PUID prefix - otherwise it could never be checked. */
    @Test
    public void everySampleFileNameStartsWithAPuidPrefix() {
        List<String> badNames = sampleFiles().stream()
                .map(File::getName)
                .filter(name -> expectedPuid(name) == null)
                .collect(Collectors.toList());
        if (!badNames.isEmpty()) {
            fail(badNames.size() + " file(s) in " + sampleFolder().getAbsolutePath()
                    + " do not start with a PUID prefix like 'fmt-477_' or 'x-fmt-88_' "
                    + "(the file name is the only source of the expected result): " + badNames);
        }
    }

    /** One sample per format: no two files in the folder may carry the same PUID prefix. */
    @Test
    public void everyFormatHasExactlyOneSample() {
        Map<String, List<String>> namesByPuid = new TreeMap<>();
        for (File file : sampleFiles()) {
            String puid = expectedPuid(file.getName());
            if (puid != null) {
                namesByPuid.computeIfAbsent(puid, key -> new ArrayList<>()).add(file.getName());
            }
        }
        List<String> duplicates = namesByPuid.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .map(entry -> entry.getKey() + " -> " + entry.getValue())
                .collect(Collectors.toList());
        if (!duplicates.isEmpty()) {
            fail("More than one sample for the same format in " + sampleFolder().getAbsolutePath()
                    + " (a variant that tests something different belongs in its own folder, "
                    + "with the reason written next to it): " + duplicates);
        }
    }

    /** Every special case must name a sample that exists and must say why. */
    @Test
    public void everySpecialCaseNamesAnExistingSampleAndGivesAReason() {
        List<String> sampleNames = sampleFiles().stream().map(File::getName).collect(Collectors.toList());
        for (Map.Entry<String, SpecialCase> entry : SPECIAL_CASES.entrySet()) {
            assertTrue(sampleNames.contains(entry.getKey()),
                    "SPECIAL_CASES has an entry for '" + entry.getKey() + "' but there is no such file in "
                            + sampleFolder().getAbsolutePath() + " - remove or rename the entry");
            assertFalse(entry.getValue().reason.trim().isEmpty(),
                    "SPECIAL_CASES entry for '" + entry.getKey() + "' has no reason");
        }
    }

    /** One test per sample file: detect it and compare with the PUID in its file name. */
    @TestFactory
    public Stream<DynamicTest> everySampleIsDetectedAsTheFormatInItsFileName() throws Exception {
        DroidSignatureVerifier verifier = new DroidSignatureVerifier(ProjectPaths.mainResource(SIGNATURE_FILE));

        return sampleFiles().stream()
                .filter(file -> expectedPuid(file.getName()) != null) // the others fail the naming test above
                .map(file -> DynamicTest.dynamicTest(file.getName(), () -> checkSample(verifier, file)));
    }

    private static void checkSample(DroidSignatureVerifier verifier, File file) throws Exception {
        String name = file.getName();
        String expected = expectedPuid(name);
        SpecialCase special = SPECIAL_CASES.get(name);

        DetectionResult[] results = verifier.detect(file);
        List<String> codes = Arrays.stream(results).map(r -> r.code).collect(Collectors.toList());
        String actual = codes.isEmpty() ? "no match" : Arrays.toString(results);

        if (special == null) {
            if (codes.isEmpty() || !codes.get(0).equals(expected)) {
                fail(name + ": expected " + expected + " as the first result but detection returned " + actual);
            }
        } else if (special.ordered) {
            if (codes.size() < special.puids.size() || !codes.subList(0, special.puids.size()).equals(special.puids)) {
                fail(name + ": expected the results to start with " + special.puids + " but detection returned "
                        + actual + ". Reason for this expectation: " + special.reason);
            }
        } else {
            if (!codes.containsAll(special.puids)) {
                fail(name + ": expected all of " + special.puids + " among the results (in any order) but detection "
                        + "returned " + actual + ". Reason for this expectation: " + special.reason);
            }
        }

        // The stream based method must agree exactly with the file based one.
        // A plain FileInputStream has no mark/reset support, so nothing is reset.
        DetectionResult[] streamResults;
        try (InputStream in = new FileInputStream(file)) {
            streamResults = verifier.detect(in);
        }
        assertEquals(asStrings(results), asStrings(streamResults),
                name + ": detect(InputStream) disagrees with detect(File)");
    }

    /**
     * The PUID a sample file must be detected as, taken from its file name, or null if the
     * name does not start with a PUID prefix. "fmt-477_..." gives "fmt/477", "x-fmt-88_..."
     * gives "x-fmt/88".
     */
    static String expectedPuid(String fileName) {
        Matcher m = PREFIX.matcher(fileName);
        if (!m.matches()) {
            return null;
        }
        return m.group(1) + "/" + m.group(2);
    }

    private static File sampleFolder() {
        return ProjectPaths.testResource(SAMPLE_FOLDER);
    }

    /** All sample files, sorted by name: regular files only, without the manifest and hidden files. */
    private static List<File> sampleFiles() {
        File[] entries = sampleFolder().listFiles();
        List<File> files = new ArrayList<>();
        if (entries != null) {
            for (File f : entries) {
                if (f.isFile() && !f.getName().equals(MANIFEST) && !f.getName().startsWith(".")) {
                    files.add(f);
                }
            }
        }
        files.sort(Comparator.comparing(File::getName));
        return files;
    }

    private static List<String> asStrings(DetectionResult[] results) {
        return Arrays.stream(results).map(DetectionResult::toString).collect(Collectors.toList());
    }

    /** An explicit expectation for one sample file, and the reason for it. */
    private static final class SpecialCase {
        final List<String> puids;
        final boolean ordered;
        final String reason;

        private SpecialCase(List<String> puids, boolean ordered, String reason) {
            this.puids = puids;
            this.ordered = ordered;
            this.reason = reason;
        }

        /** The results must start with exactly these PUIDs, in this order. */
        static SpecialCase ordered(String reason, String... puids) {
            return new SpecialCase(Arrays.asList(puids), true, reason);
        }

        /** All these PUIDs must be among the results; their order is not asserted. */
        static SpecialCase unordered(String reason, String... puids) {
            return new SpecialCase(Arrays.asList(puids), false, reason);
        }
    }
}
