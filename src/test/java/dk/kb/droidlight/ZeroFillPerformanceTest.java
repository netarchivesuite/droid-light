package dk.kb.droidlight;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * Reports how long each verifier class takes against a large, entirely
 * zero-filled file - a stress case for anchor matching, since several
 * signatures' literal anchors are themselves runs of zero bytes, every one
 * of which occurs at every single position in such a file.
 *
 * The fixture is generated into a temporary file at run time (not committed
 * as a resource - 40MB of zero bytes would needlessly bloat the repository)
 * and deleted again afterwards.
 *
 * This is a performance report only, not a correctness check - it makes no
 * assertions and there is nothing here for it to "pass" or "fail".
 *
 * The signature file's location is found by locating the project root from
 * the current working directory (see ProjectPaths.projectRoot()) and reading
 * directly from src/main/resources there - not via the classpath, which
 * depends on a build having already copied resources into an output folder.
 * This runs unchanged regardless of where the repository is checked out.
 *
 * No CLI args - meant to be run directly from an IDE, same style as
 * DroidSignatureVerifier's own main().
 */
public class ZeroFillPerformanceTest {

    public static void main(String[] args) throws Exception {
        long sizeBytes = 40L * 1024 * 1024;

        File tempFile = File.createTempFile("droid-light-zero-fill-", ".bin");
        tempFile.deleteOnExit();
        try (RandomAccessFile raf = new RandomAccessFile(tempFile, "rw")) {
            raf.setLength(sizeBytes);
        }
        System.out.println("Generated " + sizeBytes + "-byte zero-filled file: " + tempFile);

        File sigFile = ProjectPaths.mainResource("DROID_SignatureFile_V124.xml");

        DroidSignatureVerifier verifier1 = new DroidSignatureVerifier(sigFile);
        DroidSignatureAhoCorasickVerifier verifier2 = new DroidSignatureAhoCorasickVerifier(sigFile);

        System.out.println();
        System.out.println("Timing DroidSignatureVerifier.detect() ...");
        long t0 = System.nanoTime();
        DetectionResult[] results1 = verifier1.detect(tempFile);
        long t1 = System.nanoTime();

        System.out.println("Timing DroidSignatureAhoCorasickVerifier.detect() ...");
        DetectionResult[] results2 = verifier2.detect(tempFile);
        long t2 = System.nanoTime();

        System.out.println();
        System.out.printf("DroidSignatureVerifier:             %,10.1f ms  (%d result(s))%n", (t1 - t0) / 1e6, results1.length);
        System.out.printf("DroidSignatureAhoCorasickVerifier:   %,10.1f ms  (%d result(s))%n", (t2 - t1) / 1e6, results2.length);

        tempFile.delete();
    }
}
