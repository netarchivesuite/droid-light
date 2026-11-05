package dk.kb.droidlight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import dk.kb.droidlight.DroidSignatureVerifier.ByteSequenceDef;
import dk.kb.droidlight.DroidSignatureVerifier.FileRegion;
import dk.kb.droidlight.DroidSignatureVerifier.InternalSignatureDef;

/**
 * The matching code has several shortcuts that must never change a result, only how fast it is reached:
 * <ul>
 * <li>the first-byte index of {@link DroidSignatureVerifier#computeFirstByteGate} (a signature that needs a fixed
 * value of byte 0 is not tried on a file that starts with another byte),</li>
 * <li>the order in which the byte sequences of one signature are tried (cheapest first), and</li>
 * <li>the plain-array search used when the whole file is in memory, instead of the general search through
 * {@link FileRegion#byteAt(long)}.</li>
 * </ul>
 * For every sample file this test finds the signatures that match with all the shortcuts, and again by trying every
 * signature, every byte sequence in the order of the signature file, through the general search, and requires the
 * same set. The corpus-wide check of 46,000 files was done by hand when the shortcuts were made; this keeps the
 * property from being lost silently when the code is changed.
 */
public class SearchShortcutsTest {

    private static final String SIGNATURE_FILE = "DROID_SignatureFile_V125_experimental.xml";

    @Test
    public void shortcutsGiveTheSameMatchesAsATrialOfEverythingTheGeneralWay() throws Exception {
        List<InternalSignatureDef> signatures = DroidSignatureVerifier.parseSignatures(ProjectPaths.mainResource(SIGNATURE_FILE));
        File[] samples = ProjectPaths.testResource("pronom-samples").listFiles(
                f -> f.isFile() && !f.getName().equals("MANIFEST.json") && !f.getName().startsWith("."));
        assertTrue(samples != null && samples.length > 0, "No sample files found");
        Arrays.sort(samples);

        int filesWithMatch = 0;
        for (File sample : samples) {
            FileRegion fast = DroidSignatureVerifier.readBoundedRegion(sample);
            // The same bytes with an (empty) tail window: not the "whole file in the head" case, so the general search runs.
            FileRegion general = new FileRegion(fast.head, new byte[0], fast.length);
            int firstByte = fast.head.length > 0 ? fast.head[0] & 0xFF : -1;

            List<Integer> withShortcuts = new ArrayList<>();
            for (InternalSignatureDef sig : signatures) {
                if (sig.firstByteGate >= 0 && sig.firstByteGate != firstByte) continue;
                if (DroidSignatureVerifier.matchSignature(fast, sig)) withShortcuts.add(sig.id);
            }

            List<Integer> everythingGeneral = new ArrayList<>();
            for (InternalSignatureDef sig : signatures) {
                boolean all = true;
                for (ByteSequenceDef bs : sig.byteSequences) {
                    if (!DroidSignatureVerifier.matchByteSequence(general, bs)) {
                        all = false;
                        break;
                    }
                }
                if (all) everythingGeneral.add(sig.id);
            }

            assertEquals(everythingGeneral, withShortcuts, "Different signatures match " + sample.getName());
            if (!everythingGeneral.isEmpty()) filesWithMatch++;
        }
        assertTrue(filesWithMatch > samples.length / 2, "Suspiciously few sample files matched anything: " + filesWithMatch);
    }

    @Test
    public void aSignatureWithAFixedFirstByteIsRecognised() throws Exception {
        List<InternalSignatureDef> signatures = DroidSignatureVerifier.parseSignatures(ProjectPaths.mainResource(SIGNATURE_FILE));
        int gated = 0;
        for (InternalSignatureDef sig : signatures) {
            if (sig.firstByteGate >= 0) gated++;
        }
        // Most signatures start at offset 0; if this drops to nothing the index silently stopped working.
        assertTrue(gated > signatures.size() / 2, "Only " + gated + " of " + signatures.size() + " signatures have a first-byte gate");
        // And a signature that can match anywhere in the file must not have one.
        boolean someWithout = false;
        for (InternalSignatureDef sig : signatures) {
            if (sig.firstByteGate < 0) someWithout = true;
        }
        assertTrue(someWithout);
        assertFalse(signatures.isEmpty());
    }

    @Test
    public void byteSequencesAreTriedCheapestFirstAndNothingIsLost() throws Exception {
        List<InternalSignatureDef> signatures = DroidSignatureVerifier.parseSignatures(ProjectPaths.mainResource(SIGNATURE_FILE));
        for (InternalSignatureDef sig : signatures) {
            assertEquals(sig.byteSequences.size(), sig.byteSequencesCheapestFirst.length, "Signature " + sig.id);
            long previous = Long.MIN_VALUE;
            for (ByteSequenceDef bs : sig.byteSequencesCheapestFirst) {
                assertTrue(sig.byteSequences.contains(bs), "Signature " + sig.id);
                long cost = DroidSignatureVerifier.computeSequenceCost(bs);
                assertTrue(cost >= previous, "Signature " + sig.id + " is not sorted by cost");
                previous = cost;
            }
        }
    }
}
