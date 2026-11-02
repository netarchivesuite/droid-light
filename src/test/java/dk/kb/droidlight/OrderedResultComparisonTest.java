package dk.kb.droidlight;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Compares format identification between DroidSignatureVerifier and
 * DroidSignatureAhoCorasickVerifier over every file in the pronom-samples
 * resource folder, this time over the WHOLE ordered candidate list each
 * verifier returns (not just the primary/best candidate).
 *
 * One known, accepted difference exists and is deliberately exempted from
 * failing this test: for fmt-111_OLE2_Compound_Document_Format.ppt,
 * DroidSignatureAhoCorasickVerifier also reports x-fmt/88 (Microsoft
 * PowerPoint Presentation) in addition to fmt/111, because its RightFragment
 * pattern for x-fmt/88 is unbounded while DroidSignatureVerifier's own
 * MAX_ANCHOR_SEARCH_DISTANCE-based fragment search stops at 3,000 bytes and
 * so never reaches it for this particular file. This is a deliberate,
 * accepted behavioural difference, not a defect - every other file's ordered
 * list must match exactly, length and content, in order.
 *
 * Resource locations are found by locating the project root from the current
 * working directory (see ProjectPaths.projectRoot()) and reading directly from
 * src/main/resources / src/test/resources there - not via the classpath, which
 * depends on a build having already copied resources into an output folder and
 * can silently point at a stale or empty copy of that folder. This runs
 * unchanged regardless of where the repository is checked out.
 */
public class OrderedResultComparisonTest {

    /** The one file allowed to differ, and why - see class javadoc. */
    private static final String KNOWN_DIFFERENCE_FILE = "fmt-111_OLE2_Compound_Document_Format.ppt";

    @Test
    public void orderedResultListsAgree() throws Exception {
        File sigFile = ProjectPaths.mainResource("DROID_SignatureFile_V124.xml");
        File folder = ProjectPaths.testResource("pronom-samples");
        System.out.println("Signature file: " + sigFile.getAbsolutePath());
        System.out.println("Sample folder:  " + folder.getAbsolutePath());

        File[] entries = folder.listFiles();
        List<File> files = new ArrayList<>();
        if (entries != null) {
            for (File f : entries) {
                if (f.isFile()) files.add(f);
            }
        }
        files.sort(Comparator.comparing(File::getName));

        if (files.isEmpty()) {
            fail("No files found in " + folder.getAbsolutePath() + " - the folder is missing or empty, "
                    + "not a disagreement between the two verifiers.");
        }

        DroidSignatureVerifier verifier1 = new DroidSignatureVerifier(sigFile);
        DroidSignatureAhoCorasickVerifier verifier2 = new DroidSignatureAhoCorasickVerifier(sigFile);

        List<String> unexpectedDifferences = new ArrayList<>();

        for (File file : files) {
            List<String> list1 = toStrings(verifier1.detect(file));
            List<String> list2 = toStrings(verifier2.detect(file));

            boolean equal = list1.equals(list2);

            System.out.println(file.getName());
            System.out.println("  DroidSignatureVerifier:            " + list1);
            System.out.println("  DroidSignatureAhoCorasickVerifier: " + list2);
            if (!equal) {
                System.out.println("  -> DIFFERS"
                        + (file.getName().equals(KNOWN_DIFFERENCE_FILE) ? " (known, accepted difference)" : ""));
                if (!file.getName().equals(KNOWN_DIFFERENCE_FILE)) {
                    unexpectedDifferences.add(file.getName());
                }
            }
            System.out.println();
        }

        if (!unexpectedDifferences.isEmpty()) {
            fail(unexpectedDifferences.size() + " file(s) have an unexpected ordered-result-list difference "
                    + "(beyond the known " + KNOWN_DIFFERENCE_FILE + " case): " + unexpectedDifferences);
        }
    }

    private static List<String> toStrings(DetectionResult[] results) {
        List<String> out = new ArrayList<>(results.length);
        for (DetectionResult r : results) out.add(r.toString());
        return out;
    }
}
