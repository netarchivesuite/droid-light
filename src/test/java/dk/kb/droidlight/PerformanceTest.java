package dk.kb.droidlight;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Compares the speed of the official PRONOM signature file (V125) with the experimental one
 * (V125 plus the local signatures) by detecting every file in
 * <code>src/test/resources/pronom-samples</code> many times with each.
 *
 * <h2>This test is slow and is NOT run by Maven by default</h2>
 * It is tagged <code>slow</code>, and the surefire plugin in <code>pom.xml</code> excludes that tag,
 * so <code>mvn test</code> and <code>mvn clean install</code> skip it. With the default of 100 rounds
 * it takes many minutes. To run it from Maven:
 *
 * <pre>
 * mvn test -Dtest=PerformanceTest -Dtest.excludedGroups=none
 * </pre>
 *
 * From an IDE, run the class or the method directly; the exclusion only applies to Maven.
 *
 * <h2>What it does</h2>
 * One round is one detection of every sample file, once with each signature file. {@link #RUNS}
 * timed rounds are made, so each signature file is used for <code>RUNS * number of files</code>
 * detections. The two signature files take turns in going first within a round (the official one
 * first in even rounds, the experimental one first in odd rounds), so that neither gets an advantage
 * from the operating system's file cache or from the JIT compiler warming up. {@link #WARMUP_RUNS}
 * extra rounds are made before the timing starts and are not counted. The time of each signature file
 * is the sum of the time of its own passes; reading the signature file is not included.
 *
 * <p>The numbers are written to standard output when the rounds are done, with a progress line
 * every ten rounds. Like {@link ZeroFillPerformanceTest} this is a report, not a correctness check:
 * it only fails when there are no sample files.
 *
 * <p>To change the number of rounds, change {@link #RUNS} below, or pass
 * <code>-Dperftest.runs=5</code> for a quick try.
 */
@Tag("slow")
public class PerformanceTest {

    /** The number of timed rounds. Every sample file is detected this many times with each signature file. */
    private static final int RUNS = Integer.getInteger("perftest.runs", 100);

    /** Rounds made before the timing starts, so that class loading, JIT compilation and the file cache are warm. */
    private static final int WARMUP_RUNS = 1;

    private static final String OFFICIAL = "DROID_SignatureFile_V125.xml";
    private static final String EXPERIMENTAL = "DROID_SignatureFile_V125_experimental.xml";

    @Test
    public void officialVersusExperimentalSignatureFile() throws Exception {
        List<File> files = sampleFiles();
        assertFalse(files.isEmpty(), "No sample files found");
        long totalBytes = files.stream().mapToLong(File::length).sum();

        DroidSignatureVerifier official = new DroidSignatureVerifier(ProjectPaths.mainResource(OFFICIAL));
        DroidSignatureVerifier experimental = new DroidSignatureVerifier(ProjectPaths.mainResource(EXPERIMENTAL));

        System.out.println();
        System.out.printf("PerformanceTest: %,d sample files (%,.1f MB), %d timed rounds + %d warm-up round(s), "
                + "each round runs both signature files%n", files.size(), totalBytes / 1e6, RUNS, WARMUP_RUNS);

        long checksum = 0; // keeps the results in use, so nothing can be optimised away
        int officialDetected = 0;
        int experimentalDetected = 0;
        for (int i = 0; i < WARMUP_RUNS; i++) {
            long[] o = pass(official, files);
            long[] e = pass(experimental, files);
            checksum += o[1] + e[1];
            officialDetected = (int) o[2];
            experimentalDetected = (int) e[2];
        }

        long officialNanos = 0;
        long experimentalNanos = 0;
        long officialFastest = Long.MAX_VALUE;
        long experimentalFastest = Long.MAX_VALUE;
        long start = System.nanoTime();
        for (int round = 1; round <= RUNS; round++) {
            long[] o;
            long[] e;
            if (round % 2 == 1) {
                o = pass(official, files);
                e = pass(experimental, files);
            } else {
                e = pass(experimental, files);
                o = pass(official, files);
            }
            officialNanos += o[0];
            experimentalNanos += e[0];
            officialFastest = Math.min(officialFastest, o[0]);
            experimentalFastest = Math.min(experimentalFastest, e[0]);
            checksum += o[1] + e[1];
            if (round % 10 == 0 || round == RUNS) {
                System.out.printf("  round %d of %d done, %,.1f s elapsed%n", round, RUNS, (System.nanoTime() - start) / 1e9);
            }
        }

        long detections = (long) RUNS * files.size();
        System.out.println();
        System.out.printf("Each signature file: %d rounds x %,d files = %,d detections%n", RUNS, files.size(), detections);
        report("Official     " + OFFICIAL, officialNanos, officialFastest, detections);
        report("Experimental " + EXPERIMENTAL, experimentalNanos, experimentalFastest, detections);
        System.out.printf("Experimental / official: %.3f  (%+.1f%%)%n",
                (double) experimentalNanos / officialNanos, 100.0 * (experimentalNanos - officialNanos) / officialNanos);
        System.out.printf("Files with at least one result: official %,d, experimental %,d of %,d%n",
                officialDetected, experimentalDetected, files.size());
        System.out.println("(checksum of all results, only there to keep them in use: " + checksum + ")");
    }

    /**
     * Detects every file once. Returns the elapsed nanoseconds, a checksum of the results and the number of
     * files that got at least one result.
     */
    private static long[] pass(DroidSignatureVerifier verifier, List<File> files) throws Exception {
        long checksum = 0;
        long detected = 0;
        long t0 = System.nanoTime();
        for (File file : files) {
            DetectionResult[] results = verifier.detect(file);
            checksum += results.length;
            if (results.length > 0) {
                detected++;
                checksum += results[0].code.hashCode();
            }
        }
        long elapsed = System.nanoTime() - t0;
        return new long[] { elapsed, checksum, detected };
    }

    private static void report(String label, long totalNanos, long fastestRoundNanos, long detections) {
        System.out.printf("%-60s total %,10.1f s | per round %,8.2f s (fastest %,.2f s) | per file %,6.3f ms%n",
                label, totalNanos / 1e9, totalNanos / 1e9 / RUNS, fastestRoundNanos / 1e9, totalNanos / 1e6 / detections);
    }

    /** All sample files, sorted by name: regular files only, without the manifest and hidden files. */
    private static List<File> sampleFiles() {
        File[] entries = ProjectPaths.testResource("pronom-samples").listFiles();
        List<File> files = new ArrayList<>();
        if (entries != null) {
            Arrays.stream(entries)
                    .filter(f -> f.isFile() && !f.getName().equals("MANIFEST.json") && !f.getName().startsWith("."))
                    .forEach(files::add);
        }
        files.sort(Comparator.comparing(File::getName));
        return files;
    }
}
