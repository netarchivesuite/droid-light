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
 * resource folder.
 *
 * For each file, both verifiers are run and their best (primary) candidate
 * result is compared. The two verifiers are expected to always agree on the
 * primary result; the test fails, printing every disagreeing file, if they
 * do not.
 *
 * Resource locations are found by locating the project root from the current
 * working directory (see ProjectPaths.projectRoot()) and reading directly from
 * src/main/resources / src/test/resources there - not via the classpath, which
 * depends on a build having already copied resources into an output folder and
 * can silently point at a stale or empty copy of that folder. This runs
 * unchanged regardless of where the repository is checked out.
 */
public class VerifierComparisonTest {

    @Test
    public void primaryResultsAgree() throws Exception {
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

        List<String[]> rows = new ArrayList<>(); // {fileName, resultFromVerifier1, resultFromVerifier2, agree}
        List<String> disagreements = new ArrayList<>();

        for (File file : files) {
            String primary1 = primaryResultString(verifier1.detect(file));
            String primary2 = primaryResultString(verifier2.detect(file));
            boolean agree = primary1.equals(primary2);
            rows.add(new String[]{file.getName(), primary1, primary2, agree ? "" : "DIFFERS"});
            if (!agree) {
                disagreements.add(file.getName());
            }
        }

        printTable(rows);

        System.out.println();
        if (disagreements.isEmpty()) {
            System.out.println("No disagreements: all " + rows.size() + " file(s) agree on the primary result.");
        } else {
            System.out.println(disagreements.size() + " file(s) disagree on the primary result:");
            for (String fileName : disagreements) {
                System.out.println("  " + fileName);
            }
        }

        if (!disagreements.isEmpty()) {
            fail(disagreements.size() + " file(s) disagree between DroidSignatureVerifier and "
                    + "DroidSignatureAhoCorasickVerifier on the primary result: " + disagreements);
        }
    }

    /** Best (index 0) candidate result, formatted as "code  name", e.g. "fmt/1936  PCRaster" -
     *  DetectionResult.toString() already produces exactly this (plus a bracketed
     *  mime type/version suffix when the format declares one). Returns "NO MATCH"
     *  when the verifier found nothing. */
    private static String primaryResultString(DetectionResult[] results) {
        if (results == null || results.length == 0) return "NO MATCH";
        return results[0].toString();
    }

    private static void printTable(List<String[]> rows) {
        String header1 = "File";
        String header2 = "DroidSignatureVerifier";
        String header3 = "DroidSignatureAhoCorasickVerifier";
        String header4 = "";

        int col1 = header1.length();
        int col2 = header2.length();
        int col3 = header3.length();
        int col4 = header4.length();
        for (String[] row : rows) {
            col1 = Math.max(col1, row[0].length());
            col2 = Math.max(col2, row[1].length());
            col3 = Math.max(col3, row[2].length());
            col4 = Math.max(col4, row[3].length());
        }

        String rowFormat = "%-" + col1 + "s | %-" + col2 + "s | %-" + col3 + "s | %-" + col4 + "s%n";
        System.out.printf(rowFormat, header1, header2, header3, header4);
        int totalWidth = col1 + col2 + col3 + col4 + 9;
        StringBuilder sep = new StringBuilder(totalWidth);
        for (int i = 0; i < totalWidth; i++) sep.append('-');
        System.out.println(sep);
        for (String[] row : rows) {
            System.out.printf(rowFormat, row[0], row[1], row[2], row[3]);
        }
    }
}
