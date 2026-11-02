package dk.kb.droidlight;

import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression test for anchor-candidate overflow in
 * DroidSignatureAhoCorasickVerifier's single-pass anchor scan.
 *
 * Uses a tiny, synthetic signature file made for this test only - not a real
 * format signature (one signature: BOF anchor of
 * four 0x00 bytes, immediately followed by a RightFragment "AA BB", within a
 * 2000-byte BOF window) together with a 302-byte file consisting of 300
 * zero bytes followed by AA BB.
 *
 * The anchor (four zero bytes) occurs 297 times in the 300-byte zero run
 * (at every offset 0..296), but only the occurrence at offset 296 is
 * immediately followed by AA BB and therefore verifies. An anchor-candidate
 * holder that drops or overwrites candidates once some fixed capacity is
 * reached - rather than falling back to a live, unbounded search - can lose
 * exactly that occurrence while keeping only earlier, non-verifying ones.
 * Both verifiers must still identify the file correctly.
 *
 * Resource locations are found by locating the project root from the current
 * working directory (see ProjectPaths.projectRoot()) and reading directly from
 * src/test/resources there - not via the classpath, which depends on a build
 * having already copied resources into an output folder. This runs unchanged
 * regardless of where the repository is checked out.
 */
public class HitBufferOverflowTest {

    @Test
    public void overflowingAnchorStillMatches() throws Exception {
        File sigFile = ProjectPaths.testResource("overflow-test/unittest-fake-overflow-signature-file.xml");
        File targetFile = ProjectPaths.testResource("overflow-test/zero-run-plus-aabb.bin");

        DroidSignatureVerifier verifier1 = new DroidSignatureVerifier(sigFile);
        DroidSignatureAhoCorasickVerifier verifier2 = new DroidSignatureAhoCorasickVerifier(sigFile);

        DetectionResult[] results1 = verifier1.detect(targetFile);
        DetectionResult[] results2 = verifier2.detect(targetFile);

        String result1 = results1.length > 0 ? results1[0].toString() : "NO MATCH";
        String result2 = results2.length > 0 ? results2[0].toString() : "NO MATCH";

        System.out.println("DroidSignatureVerifier:            " + result1);
        System.out.println("DroidSignatureAhoCorasickVerifier:  " + result2);

        assertEquals("test-fmt/1", results1.length > 0 ? results1[0].code : "NO MATCH",
                "DroidSignatureVerifier did not identify the fixture file");
        assertEquals("test-fmt/1", results2.length > 0 ? results2[0].code : "NO MATCH",
                "DroidSignatureAhoCorasickVerifier did not identify the fixture file");
    }
}
