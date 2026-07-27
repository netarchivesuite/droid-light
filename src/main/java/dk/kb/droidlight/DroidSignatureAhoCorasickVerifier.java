package dk.kb.droidlight;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.*;

/**
 * DroidSignatureAhoCorasickVerifier - combines the single-pass Aho-Corasick anchor
 * scanning architecture (AhoCorasickDroidPrototype2) with DroidSignatureVerifier's
 * validated matching semantics (offset-window-applies-to-chain-end, fragment
 * alternatives, multi-ByteSequence AND, priority resolution, etc).
 *
 * WHY THIS EXISTS
 * -----------------
 * DroidSignatureVerifier - correct as it now is - still searches for each of its
 * ~2,258 signatures' anchors independently: one small window-scan per signature,
 * even though MAX_ANCHOR_SEARCH_DISTANCE keeps each of those scans cheap. That's
 * 2,258 separate scans of (mostly) the same bytes. This class instead compiles
 * every signature's FIXED (reference-point-anchored) literal anchor into ONE
 * combined Aho-Corasick automaton and finds ALL of their occurrences across the
 * WHOLE target file in a SINGLE pass - O(file_length), independent of how many
 * signatures are loaded. The (already-correct) fragment-chain/offset-window
 * verification logic then runs only against those precomputed candidate
 * positions, reusing DroidSignatureVerifier's own matchChainedSubSequence,
 * verifyRightFragmentChain, verifyLeftFragmentChain, and applyPriorityResolution
 * directly (all package-private static members of DroidSignatureVerifier, reused
 * here rather than duplicated, since both classes share the same default package).
 *
 * A NICE SIDE EFFECT: MAX_ANCHOR_SEARCH_DISTANCE BECOMES UNNECESSARY
 * ----------------------------------------------------------------------
 * DroidSignatureVerifier needed MAX_ANCHOR_SEARCH_DISTANCE as a pragmatic safety
 * cap AND, less desirably, as a de-facto (imprecise) false-positive-reduction
 * lever - shrinking the window reduced some coincidental matches, but also risked
 * cutting off genuine matches that legitimately needed a wider search (see the
 * whole MP3/ID3v2-tag saga earlier). Here, because Aho-Corasick finds ALL real
 * anchor occurrences across the entire file cheaply regardless of distance, there
 * is no need to guess a window size for correctness purposes at all - the
 * offset-window check (which DroidSignatureVerifier already validates correctly,
 * after its own earlier bug fix) is what legitimately decides whether a candidate
 * is valid, not an arbitrary distance cutoff. The only remaining reason to cap
 * anything is the same reason DROID itself needs a maxBytesToScan-style safety
 * valve: a pathological signature with many chained wildcard-range fragments (see
 * signature 280, the original hang bug) could still be expensive if tried against
 * too many candidate anchor positions - see MAX_CANDIDATES_TO_TRY below, which
 * bounds candidate COUNT (the real cost driver) rather than search DISTANCE (an
 * indirect, imprecise proxy for it).
 *
 * WHAT IS NOT SPED UP BY THIS CLASS
 * -------------------------------------
 * Only the FIXED (first, reference-point-anchored) SubSequence of each
 * ByteSequence gets the Aho-Corasick treatment. Chained (non-first) SubSequences
 * (e.g. MP4 signature 278's "moov" search after "ftyp") still use
 * DroidSignatureVerifier.matchChainedSubSequence unchanged - a plain per-signature
 * window scan, same as before. These are usually far fewer and far more tightly
 * bounded by their own SubSeqMinOffset/MaxOffset in practice, so this was judged
 * not worth the added complexity of compiling a second, chained-context automaton
 * - but it is a known asymmetry, not an oversight.
 *
 * MEASURED RESULT - A REAL, HONEST TRADEOFF, NOT A UNIFORM WIN
 * -------------------------------------------------------------
 * All results were cross-checked identical to DroidSignatureVerifier across every
 * test file used throughout this project's development (GIF positive/negative,
 * WebP, both MP3s, MP4) - this class is a drop-in replacement for correctness.
 * Performance, however, genuinely depends on file size and content, in a way worth
 * understanding rather than assuming Aho-Corasick automatically wins:
 *
 *   File                         DroidSignatureVerifier   This class
 *   fake.gif (27 bytes)          73 ms                    27 ms   (2.7x faster)
 *   image.webp (55.8 KB)         570 ms                   66 ms   (8.6x faster)
 *   real MP3 (6.2 MB)            232 ms                   668 ms  (2.9x SLOWER)
 *   real MP4 (23.3 MB)           586 ms                   1276 ms (2.2x SLOWER)
 *
 * WHY IT'S SLOWER ON LARGER FILES - AND WHY THAT'S THE RIGHT LESSON, NOT A BUG
 * -------------------------------------------------------------------------------
 * DroidSignatureVerifier's MAX_ANCHOR_SEARCH_DISTANCE bounds its fixed-anchor
 * search to a small, FILE-SIZE-INDEPENDENT window (2000 bytes) near each
 * ByteSequence's reference point. It never looks at most of a large file at all -
 * cost there is roughly signature_count x window_size, constant regardless of
 * whether the file is 6 MB or 180 MB (confirmed earlier: ~145ms on a 6MB MP3 and
 * ~430ms on a 180MB MP3 - barely different).
 *
 * This class's Aho-Corasick stage, by contrast, must make one pass over the
 * ENTIRE file to find every literal anchor occurrence anywhere in it - a genuine
 * O(file_length) cost. For SMALL/MEDIUM files that's cheap and the win from
 * checking all 2,258 signatures' anchors in one combined pass (instead of 2,258
 * separate small-window scans) dominates. For LARGE files, the O(file_length)
 * full-file pass itself becomes the bigger cost, and DroidSignatureVerifier's
 * already-bounded, size-independent search wins instead.
 *
 * (An earlier, unbounded version of this class's hit-collection was far worse
 * still - a real MP3 produced 3.68 MILLION raw anchor hits, an MP4 8.86 MILLION,
 * because short/common literal anchors coincidentally match constantly in noisy
 * compressed media data; only the closest-to-reference-point handful of those
 * are ever useful, so hit collection is now bounded by MAX_CANDIDATES_TO_TRY
 * during the scan itself, cutting both files down to ~14,000 hits and bringing
 * runtime down substantially - but the fundamental full-file-pass cost for large
 * files remains, since that part is inherent to the single-pass architecture.)
 *
 * PRACTICAL TAKEAWAY: this class is a clear win for small-to-medium files, and a
 * real (if now modest, ~2-3x) regression for large files, compared to
 * DroidSignatureVerifier's already-efficient bounded-window design. Which one is
 * "better" depends entirely on the expected file-size distribution of whatever
 * it's being used against - there is no universally superior choice between them
 * demonstrated here.
 */
public class DroidSignatureAhoCorasickVerifier {

    /** Safety cap on how many precomputed candidate anchor positions will actually
     *  be tried (fragment-chain-verified) per (signature, ByteSequence) pair. See
     *  class javadoc: this replaces MAX_ANCHOR_SEARCH_DISTANCE's safety-valve role,
     *  now based on candidate COUNT (the real cost driver) instead of search
     *  distance. Candidates are tried closest-to-reference-point first, so this
     *  cap only matters for signatures whose anchor happens to occur an unusually
     *  large number of times in one file (as signature 280's did).
     */
    static int MAX_CANDIDATES_TO_TRY = 50;

    /** Aho-Corasick trie node - flat 256-entry array transition table (see
     *  AhoCorasickDroidPrototype2 for the earlier standalone version and its
     *  measured throughput vs a HashMap-based trie). */
    static final class TrieNode {
        final TrieNode[] children = new TrieNode[256];
        TrieNode fail;
        // Each output entry identifies which (signature, ByteSequence) pair's fixed
        // anchor ends at this trie node: {signatureIndex, byteSequenceIndex}.
        List<int[]> outputs;
    }

    private final List<DroidSignatureVerifier.InternalSignatureDef> signatures;
    private final List<DroidSignatureVerifier.FileFormatDef> formats;
    private final TrieNode trieRoot = new TrieNode();

    /**
     * Parses the given DROID signature file once (reusing
     * DroidSignatureVerifier's own parser directly - no duplicated parsing logic),
     * then compiles every signature's fixed anchor into one combined Aho-Corasick
     * automaton.
     */
    public DroidSignatureAhoCorasickVerifier(File signatureFile) throws Exception {
        System.out.println("Parsing full signature structure (anchors + fragments + endianness): " + signatureFile);
        long t0 = System.nanoTime();
        this.signatures = DroidSignatureVerifier.parseSignatures(signatureFile);
        this.formats = DroidSignatureVerifier.parseFileFormats(signatureFile);
        long t1 = System.nanoTime();
        System.out.printf("  Parsed %,d signatures and %,d file formats in %.1f ms%n",
                signatures.size(), formats.size(), (t1 - t0) / 1e6);

        int anchorCount = 0;
        for (int sigIdx = 0; sigIdx < signatures.size(); sigIdx++) {
            DroidSignatureVerifier.InternalSignatureDef sig = signatures.get(sigIdx);
            for (int bsIdx = 0; bsIdx < sig.byteSequences.size(); bsIdx++) {
                DroidSignatureVerifier.ByteSequenceDef bs = sig.byteSequences.get(bsIdx);
                DroidSignatureVerifier.SubSequenceDef fixedSub = getFixedSubSequence(bs);
                if (fixedSub == null || fixedSub.anchor == null) continue;
                byte[] literalAnchor = toLiteralBytesOrNull(fixedSub.anchor);
                // toLiteralBytesOrNull returns null if the anchor contains a wildcard
                // (range/NOT) - in practice DROID's primary <Sequence> never does
                // (confirmed by inspection of the real V111/V124 signature files),
                // but this is a defensive check, not an assumption.
                if (literalAnchor == null || literalAnchor.length == 0) continue;
                boolean isEofDirected = (bs.reference == DroidSignatureVerifier.Reference.EOF);
                addToTrie(literalAnchor, sigIdx, bsIdx, literalAnchor.length, isEofDirected);
                anchorCount++;
            }
        }
        buildFailureLinks();
        long t2 = System.nanoTime();
        System.out.printf("  Compiled %,d fixed anchors into one combined Aho-Corasick automaton in %.1f ms%n",
                anchorCount, (t2 - t1) / 1e6);
    }

    // ------------------------------------------------------------------
    // Trie construction helpers
    // ------------------------------------------------------------------

    /** The "fixed" SubSequence of a ByteSequence is the one directly anchored to
     *  its BOF/EOF/Anywhere reference point - the first one in Position order for
     *  BOF/Anywhere, or the LAST one in Position order for EOF (DROID processes
     *  EOF-referenced SubSequences in reverse - see
     *  DroidSignatureVerifier.matchByteSequence, which this mirrors). */
    static DroidSignatureVerifier.SubSequenceDef getFixedSubSequence(DroidSignatureVerifier.ByteSequenceDef bs) {
        List<DroidSignatureVerifier.SubSequenceDef> subs = new ArrayList<>(bs.subSequences);
        if (subs.isEmpty()) return null;
        subs.sort(Comparator.comparingInt(s -> s.position));
        if (bs.reference == DroidSignatureVerifier.Reference.EOF) Collections.reverse(subs);
        return subs.get(0);
    }

    /** Converts a parsed anchor pattern to plain literal bytes for the Aho-Corasick
     *  trie, or null if it contains any wildcard element (Aho-Corasick here only
     *  indexes exact-byte anchors; wildcard verification still happens correctly
     *  in the fragment-chain stage, which is unaffected by this). */
    static byte[] toLiteralBytesOrNull(List<DroidSignatureVerifier.PatternElement> pattern) {
        byte[] out = new byte[pattern.size()];
        for (int i = 0; i < pattern.size(); i++) {
            DroidSignatureVerifier.PatternElement pe = pattern.get(i);
            if (pe.kind != DroidSignatureVerifier.Kind.LITERAL) return null;
            out[i] = (byte) pe.a;
        }
        return out;
    }

    void addToTrie(byte[] pattern, int sigIdx, int bsIdx, int anchorLen, boolean isEofDirected) {
        TrieNode cur = trieRoot;
        for (byte b : pattern) {
            int idx = b & 0xFF;
            if (cur.children[idx] == null) cur.children[idx] = new TrieNode();
            cur = cur.children[idx];
        }
        if (cur.outputs == null) cur.outputs = new ArrayList<>();
        cur.outputs.add(new int[]{sigIdx, bsIdx, anchorLen, isEofDirected ? 1 : 0});
    }

    void buildFailureLinks() {
        Deque<TrieNode> queue = new ArrayDeque<>();
        trieRoot.fail = trieRoot;
        for (int b = 0; b < 256; b++) {
            TrieNode child = trieRoot.children[b];
            if (child != null) {
                child.fail = trieRoot;
                queue.add(child);
            }
        }
        while (!queue.isEmpty()) {
            TrieNode cur = queue.poll();
            for (int b = 0; b < 256; b++) {
                TrieNode child = cur.children[b];
                if (child == null) continue;
                TrieNode f = cur.fail;
                while (f != trieRoot && f.children[b] == null) f = f.fail;
                TrieNode candidate = f.children[b];
                child.fail = (candidate != null && candidate != child) ? candidate : trieRoot;
                if (child.fail.outputs != null) {
                    if (child.outputs == null) child.outputs = new ArrayList<>();
                    child.outputs.addAll(child.fail.outputs);
                }
                queue.add(child);
            }
        }
    }

    // ------------------------------------------------------------------
    // Single-pass anchor scan
    // ------------------------------------------------------------------

    /**
     * Scans the whole file ONCE, returning, for every (signatureIndex,
     * byteSequenceIndex) pair whose fixed anchor occurs anywhere in the file, the
     * list of start positions where it occurs (in ascending file-position order,
     * since that's the order a single forward pass naturally produces them in).
     */
    Map<Integer, Map<Integer, List<Integer>>> scanForAnchors(byte[] data) {
        Map<Integer, Map<Integer, List<Integer>>> hits = new HashMap<>();
        TrieNode cur = trieRoot;
        for (int i = 0; i < data.length; i++) {
            int b = data[i] & 0xFF;
            while (cur != trieRoot && cur.children[b] == null) cur = cur.fail;
            TrieNode next = cur.children[b];
            cur = (next != null) ? next : trieRoot;
            if (cur.outputs != null) {
                for (int[] out : cur.outputs) {
                    int sigIdx = out[0];
                    int bsIdx = out[1];
                    int anchorLen = out[2]; // precomputed at construction time - see addToTrie
                    boolean isEofDirected = (out[3] == 1);
                    int startPos = i - anchorLen + 1;

                    Map<Integer, List<Integer>> bySig = hits.computeIfAbsent(sigIdx, k -> new HashMap<>());
                    List<Integer> positions = bySig.computeIfAbsent(bsIdx, k -> new ArrayList<>());

                    // BOUNDED COLLECTION - the real fix for the ~3.7M/8.9M hit counts
                    // measured on real MP3/MP4 files (see class javadoc addendum
                    // below): only the MAX_CANDIDATES_TO_TRY positions closest to the
                    // relevant reference point are ever useful (matchFixedSubSequence-
                    // UsingHits only tries that many anyway), so there is no reason to
                    // store every one of potentially millions of coincidental hits for
                    // a short, common anchor in noisy compressed media data.
                    //   - BOF-directed: positions arrive in ascending order, so the
                    //     first MAX_CANDIDATES_TO_TRY seen are already the closest to
                    //     BOF (position 0) - once the cap is reached, later (larger,
                    //     less useful) positions are simply skipped.
                    //   - EOF-directed: we want the positions closest to true EOF,
                    //     i.e. the LARGEST ones - since positions still arrive in
                    //     ascending order, each new one is "better" than the smallest
                    //     one currently held once the cap is full, so the smallest
                    //     stored position is evicted to make room.
                    if (positions.size() < MAX_CANDIDATES_TO_TRY) {
                        positions.add(startPos);
                    } else if (isEofDirected) {
                        positions.remove(0); // smallest (least useful for EOF) - ascending order
                        positions.add(startPos);
                    }
                    // else (BOF-directed, cap reached): skip - later positions are
                    // strictly further from BOF and would never be tried anyway.
                }
            }
        }
        return hits;
    }

    // ------------------------------------------------------------------
    // Verification using precomputed anchor candidates (instead of
    // DroidSignatureVerifier.matchFixedSubSequence's own byte-by-byte candidate
    // search - everything else is reused unchanged).
    // ------------------------------------------------------------------

    /** Same semantics as DroidSignatureVerifier.matchFixedSubSequence (offset
     *  window applies to the fragment chain's END relative to the reference
     *  point, candidates tried closest-to-reference-point first), but iterating
     *  over precomputed candidate positions instead of scanning for them, and
     *  capped by MAX_CANDIDATES_TO_TRY instead of MAX_ANCHOR_SEARCH_DISTANCE. */
    static int matchFixedSubSequenceUsingHits(byte[] data, DroidSignatureVerifier.SubSequenceDef sub,
                                               int referencePosition, boolean searchBackward,
                                               List<Integer> anchorStartPositions) {
        if (sub.anchor == null || anchorStartPositions == null || anchorStartPositions.isEmpty()) return -1;
        int maxOffset = (sub.maxSeqOffset >= 0) ? sub.maxSeqOffset : Integer.MAX_VALUE / 2;
        int minOffset = sub.minSeqOffset;
        int anchorLen = DroidSignatureVerifier.patternLength(sub.anchor);
        int n = anchorStartPositions.size();

        if (searchBackward) {
            // EOF-anchored: candidates closest to true EOF first = largest position first.
            for (int idx = n - 1, tried = 0; idx >= 0 && tried < MAX_CANDIDATES_TO_TRY; idx--, tried++) {
                int candidate = anchorStartPositions.get(idx);
                int afterAnchor = candidate + anchorLen;
                int chainEnd = DroidSignatureVerifier.verifyRightFragmentChain(data, afterAnchor, sub.rightFragments);
                if (chainEnd >= 0) {
                    int distanceFromReference = referencePosition - chainEnd;
                    if (distanceFromReference >= minOffset && distanceFromReference <= maxOffset) {
                        int beforeLeft = DroidSignatureVerifier.verifyLeftFragmentChain(data, candidate, sub.leftFragments);
                        if (beforeLeft >= 0) return chainEnd;
                    }
                }
            }
            return -1;
        } else {
            // BOF-anchored: candidates closest to BOF first = smallest position first
            // (already the natural ascending order the scan produced them in).
            for (int idx = 0, tried = 0; idx < n && tried < MAX_CANDIDATES_TO_TRY; idx++, tried++) {
                int candidate = anchorStartPositions.get(idx);
                int beforeLeft = DroidSignatureVerifier.verifyLeftFragmentChain(data, candidate, sub.leftFragments);
                if (beforeLeft >= 0) {
                    int distanceFromReference = beforeLeft - referencePosition;
                    if (distanceFromReference >= minOffset && distanceFromReference <= maxOffset) {
                        int afterAnchor = candidate + anchorLen;
                        int chainEnd = DroidSignatureVerifier.verifyRightFragmentChain(data, afterAnchor, sub.rightFragments);
                        if (chainEnd >= 0) return chainEnd;
                    }
                }
            }
            return -1;
        }
    }

    /** Same structure as DroidSignatureVerifier.matchByteSequence: the fixed
     *  (first/reference-anchored) SubSequence now uses precomputed hits; any
     *  chained (non-first) SubSequences reuse
     *  DroidSignatureVerifier.matchChainedSubSequence unchanged. */
    boolean matchByteSequenceUsingHits(byte[] data, DroidSignatureVerifier.ByteSequenceDef bs,
                                        int sigIdx, int bsIdx,
                                        Map<Integer, Map<Integer, List<Integer>>> anchorHits) {
        List<DroidSignatureVerifier.SubSequenceDef> subs = new ArrayList<>(bs.subSequences);
        subs.sort(Comparator.comparingInt(s -> s.position));
        boolean backward = (bs.reference == DroidSignatureVerifier.Reference.EOF);
        if (backward) Collections.reverse(subs);

        int referencePosition = backward ? data.length : 0;
        boolean first = true;
        int cursor = -1;
        for (DroidSignatureVerifier.SubSequenceDef sub : subs) {
            int result;
            if (first) {
                List<Integer> hits = null;
                Map<Integer, List<Integer>> bySig = anchorHits.get(sigIdx);
                if (bySig != null) hits = bySig.get(bsIdx);
                result = matchFixedSubSequenceUsingHits(data, sub, referencePosition, backward, hits);
            } else {
                result = DroidSignatureVerifier.matchChainedSubSequence(data, sub, cursor);
            }
            if (result < 0) return false;
            cursor = result;
            first = false;
        }
        return true;
    }

    boolean matchSignatureUsingHits(byte[] data, DroidSignatureVerifier.InternalSignatureDef sig, int sigIdx,
                                     Map<Integer, Map<Integer, List<Integer>>> anchorHits) {
        for (int bsIdx = 0; bsIdx < sig.byteSequences.size(); bsIdx++) {
            if (!matchByteSequenceUsingHits(data, sig.byteSequences.get(bsIdx), sigIdx, bsIdx, anchorHits)) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Public API - identical shape to DroidSignatureVerifier's detect(), so the
    // two classes are drop-in interchangeable.
    // ------------------------------------------------------------------

    /**
     * Runs full format detection for one file and returns up to 10 candidate
     * results, best guess first - identical contract and ordering to
     * DroidSignatureVerifier.detect() (see its javadoc), just computed via the
     * single-pass Aho-Corasick anchor stage instead of independent per-signature
     * scans.
     */
    public DetectionResult[] detect(File targetFile) throws Exception {
        byte[] data = Files.readAllBytes(targetFile.toPath());
        System.out.println("\nVerifying against: " + targetFile + " (" + data.length + " bytes)");
        return detectFromBytes(data);
    }

    /**
     * Same as detect(File), but reads from an InputStream instead - identical
     * contract to DroidSignatureVerifier.detect(InputStream) (see its javadoc for
     * the mark/reset behavior and rationale - this mirrors it exactly).
     *
     * @param in the stream to identify; not closed by this method - the caller
     *           retains ownership and is responsible for closing it
     * @return array of 0 to 10 DetectionResults, index 0 = top candidate
     */
    public DetectionResult[] detect(InputStream in) throws Exception {
        if (in.markSupported()) {
            try {
                in.reset();
            } catch (IOException e) {
                System.out.println("  (reset() failed on the supplied stream, reading from current position: " + e + ")");
            }
        }
        byte[] data = in.readAllBytes();
        System.out.println("\nVerifying against: <input stream> (" + data.length + " bytes)");
        return detectFromBytes(data);
    }

    /**
     * The actual detection logic, shared by both detect(File) and
     * detect(InputStream) - mirrors DroidSignatureVerifier's own
     * detectFromBytes() split, just using the Aho-Corasick anchor stage instead
     * of independent per-signature scans.
     */
    private DetectionResult[] detectFromBytes(byte[] data) throws Exception {
        long t0 = System.nanoTime();
        Map<Integer, Map<Integer, List<Integer>>> anchorHits = scanForAnchors(data);
        long t1 = System.nanoTime();
        System.out.printf("  Single-pass Aho-Corasick anchor scan completed in %.1f ms%n", (t1 - t0) / 1e6);

        List<Integer> matchedSignatureIds = new ArrayList<>();
        for (int sigIdx = 0; sigIdx < signatures.size(); sigIdx++) {
            DroidSignatureVerifier.InternalSignatureDef sig = signatures.get(sigIdx);
            if (matchSignatureUsingHits(data, sig, sigIdx, anchorHits)) {
                matchedSignatureIds.add(sig.id);
            }
        }
        long t2 = System.nanoTime();
        System.out.printf("  Verification of all %,d signatures (using precomputed anchors) completed in %.1f ms (no hang, no backtracking)%n",
                signatures.size(), (t2 - t1) / 1e6);

        if (matchedSignatureIds.isEmpty()) {
            System.out.println("  No signatures matched.");
            return new DetectionResult[0];
        }

        List<DroidSignatureVerifier.FileFormatDef> matchedFormats = new ArrayList<>();
        for (DroidSignatureVerifier.FileFormatDef fmt : formats) {
            for (int sigId : fmt.signatureIds) {
                if (matchedSignatureIds.contains(sigId)) {
                    matchedFormats.add(fmt);
                    break;
                }
            }
        }
        System.out.println("  Raw matched signature IDs: " + matchedSignatureIds
                + " (" + matchedFormats.size() + " format(s) before priority resolution)");

        List<DroidSignatureVerifier.FileFormatDef> resolved = DroidSignatureVerifier.applyPriorityResolution(matchedFormats);
        System.out.println("  After priority resolution:");
        for (DroidSignatureVerifier.FileFormatDef fmt : resolved) {
            System.out.println("    -> " + fmt.puid + "  " + fmt.name);
        }

        List<DroidSignatureVerifier.FileFormatDef> ordered = new ArrayList<>(resolved);
        for (DroidSignatureVerifier.FileFormatDef fmt : matchedFormats) {
            if (!ordered.contains(fmt)) ordered.add(fmt);
        }

        int count = Math.min(10, ordered.size());
        DetectionResult[] results = new DetectionResult[count];
        for (int i = 0; i < count; i++) {
            DroidSignatureVerifier.FileFormatDef fmt = ordered.get(i);
            results[i] = new DetectionResult(fmt.puid, fmt.name, fmt.mimeType);
        }
        return results;
    }

    // ------------------------------------------------------------------
    // Demo driver - same hardcoded-variable style as DroidSignatureVerifier's own
    // main(), for direct IDE "Run" usage.
    // ------------------------------------------------------------------


    public static void main(String[] args) throws Exception {
        String signatureFile = "/home/teg/eclipse-workspace/droid-light/src/main/resources/DROID_SignatureFile_V124.xml";
//      String scanFile = "/home/teg/Desktop/Evald_Tang_Kristensen_samling-oversigt.pdf";
//      String scanFile = "/home/teg/Music/SOTB/02-Tim-Wright-CoLD-SToRAGE-Castle-burger-bar-mix.mp3";
        String scanFile = "/home/teg/Downloads/Bozzy_and_the_Bint_-_BBKB_20171129.mp3";

        File sigFile = new File(signatureFile);
        File targetFile = new File(scanFile);
           
        DroidSignatureAhoCorasickVerifier droid = new DroidSignatureAhoCorasickVerifier(sigFile);
        DetectionResult[] results = droid.detect(targetFile);
        System.out.println(results[0]);
    }
}