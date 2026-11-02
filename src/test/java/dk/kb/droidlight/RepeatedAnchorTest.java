package dk.kb.droidlight;

import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A signature anchor that occurs very many times must not cause the one
 * occurrence that actually verifies to be missed.
 *
 * Uses a tiny, synthetic signature file made for this test only - not a real
 * format signature (one signature: BOF anchor of four 0x00 bytes, immediately
 * followed by a RightFragment "AA BB", within a 2000-byte BOF window) together
 * with a 302-byte file consisting of 300 zero bytes followed by AA BB.
 *
 * The anchor (four zero bytes) occurs 297 times in the 300-byte zero run
 * (at every offset 0..296), but only the occurrence at offset 296 is
 * immediately followed by AA BB and therefore verifies. The detector must try
 * the anchor occurrences until it reaches that one, however many earlier,
 * non-verifying occurrences there are, and identify the file.
 *
 * Resource locations are found by locating the project root from the current
 * working directory (see ProjectPaths.projectRoot()) and reading directly from
 * src/test/resources there - not via the classpath, which depends on a build
 * having already copied resources into an output folder. This runs unchanged
 * regardless of where the repository is checked out.
 */
public class RepeatedAnchorTest {

    @Test
    public void repeatedAnchorStillMatches() throws Exception {
        File sigFile = ProjectPaths.testResource("repeating-anchor/unittest-fake-repeating-anchor-signature-file.xml");
        File targetFile = ProjectPaths.testResource("repeating-anchor/zero-run-plus-aabb.bin");

        DroidSignatureVerifier verifier = new DroidSignatureVerifier(sigFile);

        DetectionResult[] results = verifier.detect(targetFile);

        System.out.println("DroidSignatureVerifier: " + (results.length > 0 ? results[0].toString() : "NO MATCH"));

        assertEquals("test-fmt/1", results.length > 0 ? results[0].code : "NO MATCH",
                "DroidSignatureVerifier did not identify the fixture file");
    }
}
