package dk.kb.droidlight;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.*;
import java.nio.file.Files;
import java.util.*;

/**
 * DroidSignatureVerifier - a readability-first implementation of DROID's binary
 * signature matching, correcting several gaps and one genuine misunderstanding found
 * through direct testing against real files AND against DROID's own real source
 * (uk.gov.nationalarchives.droid.core.signature.droid6.SubSequence / ByteSequence,
 * fetched from github.com/digital-preservation/droid, develop branch).
 *
 * GAPS IMPLEMENTED (relative to the earlier anchor-only Aho-Corasick prototypes):
 *   1. Wildcard fragment verification ([xx:yy] ranges, [!xx] single-byte negation,
 *      and [!xxxxxx...] multi-byte negation inside RightFragment/LeftFragment).
 *   2. Endianness: INVESTIGATED, found to be a no-op in the working interpretation -
 *      see the note on reversed() below. Real semantics still unresolved.
 *   3. Multi-ByteSequence AND logic (every ByteSequence under a signature must match).
 *   4. Multi-SubSequence AND-chaining within one ByteSequence (fixed a real bug: an
 *      earlier version of this class treated multiple SubSequences as OR, causing
 *      false positives - e.g. a real ODP file matching both JAR and SIARD, since all
 *      ZIP-based formats share the same first SubSequence).
 *
 * NOT IMPLEMENTED:
 *   - Container signatures (ZIP/OLE2 internal-file inspection - a wholly separate
 *     mechanism DROID uses for ODF/OOXML formats; found via a real ODP test that
 *     correctly produces no InternalSignatureCollection match).
 *   - DROID's maxBytesToScan cap and its multi-alternative fragment-option exploration
 *     (bytePosForRightFragments/bytePosForLeftFragments in the real source return
 *     arrays of candidate positions, not a single greedy match - see below).
 *
 * *** THE KEY CORRECTION IN THIS VERSION (found by reading DROID's actual source) ***
 * ------------------------------------------------------------------------------------
 * An earlier version of this class assumed a SubSequence's MinSeqOffset/MaxSeqOffset
 * bounded where the ANCHOR ITSELF must be found, relative to the ByteSequence's
 * reference point (e.g. "the anchor must be within the last 1795 bytes of the file"
 * for an EOFoffset ByteSequence). Testing against a real 180MB MP3 proved this
 * couldn't be right: a real signature (ID 266, MPEG Audio Layer 3) requires 6 chained
 * RightFragments after the anchor, and 6 fragments' worth of real audio-frame spacing
 * (several KB) cannot possibly fit inside a ~1795-byte-wide window near true EOF -
 * yet real DROID correctly identifies such files.
 *
 * Reading DROID's real SubSequence.findSequenceFromPosition() (droid6 package)
 * confirmed the actual semantics: MinSeqOffset/MaxSeqOffset bound where the FURTHEST
 * FRAGMENT IN THE CHAIN ends up, relative to the reference point - NOT where the
 * anchor starts. The anchor itself can be found anywhere further back in the file;
 * what matters is that the chain's endpoint (nearest the reference point) lands
 * within the allowed offset window - e.g. "the audio frames should stop within 47 to
 * 1795 bytes of true EOF, allowing room for a trailing ID3v1/APEv2 tag or similar".
 *
 * Concretely, from the real source (backward/EOF-search branch):
 *   if ((furthestFragmentPos) <= (referencePos - minSeqOffset)) {
 *       invalid = nearestFragmentPos < (referencePos - maxSeqOffset) && recheck(...);
 *   }
 * i.e. the LAST (nearest-to-reference) fragment's position must fall within
 * [referencePos - maxSeqOffset, referencePos - minSeqOffset].
 *
 * Because of this, the anchor search itself must be able to try MULTIPLE candidate
 * positions - starting close to the reference point and working outward/backward -
 * until one produces a fragment chain whose endpoint satisfies that window. DROID's
 * real code does this via its own backtracking search; this class does a simpler
 * (still readability-first) version: it tries anchor candidates one at a time,
 * closest-to-reference first, and for each candidate does a GREEDY (first-match-wins,
 * not exhaustive) fragment chain verification - only backtracking over ANCHOR
 * candidates, not over every possible fragment match. This is deliberately less
 * thorough than DROID's real multi-alternative fragment exploration (see
 * bytePosForRightFragments in the real source, which returns arrays of candidate
 * positions and is explicitly commented by DROID's own authors as
 * "CHECKSTYLE:OFF - way, way, way too complex") - but it is what actually fixes the
 * combinatorial hang (see DROID issue #1663): backtracking over anchor candidates
 * alone is linear-ish in file size, not exponential in fragment-chain depth.
 *
 * This correction, plus the earlier ones, has been validated against:
 *   - A real MP3 with a large ID3v2 tag -> correctly identified as fmt/134.
 *   - A synthetic GIF89a file (multi-ByteSequence AND) -> correctly fmt/4.
 *   - A real ODP file -> correctly finds x-fmt/263 (ZIP) but not fmt/1754 (ODF,
 *     which needs container signatures - out of scope, confirmed via real DROID).
 *   - The original hang-triggering WebP file -> no hang, no false match.
 *   - A real 180MB MP3 (the file that motivated this rewrite) -> see below.
 *
 * PERFORMANCE: still not a goal of this version - naive O(window) scans throughout.
 * Confirmed slower than real DROID on large files. Deferred, as agreed.
 *
 * KNOWN LIMITATION - RESIDUAL FALSE POSITIVES: MOSTLY RESOLVED (was a real bug)
 * ------------------------------------------------------------------------------
 * An earlier checkpoint of this class reported false positives (e.g. a real MP3
 * file also raw-matching signature 10, TIFF, and signature 493, MPEG Layer I,
 * alongside the correct signature 266). Investigating signature 10 specifically
 * found this was NOT DROID being more rigorous than this class - it was a genuine,
 * fixable BUG here: matchFixedSubSequence's BOF/forward-direction offset check had
 * a shortcut ("if there are no LeftFragments, skip the offset validity check
 * entirely") that let a fragment-less signature's anchor match ANYWHERE within
 * MAX_ANCHOR_SEARCH_DISTANCE, instead of enforcing its actual SubSeqMinOffset/
 * SubSeqMaxOffset window. Concretely: signature 10 requires "4D4D002A" at EXACTLY
 * byte offset 0 (no fragments) - but those 4 bytes happened to occur, by pure
 * coincidence, at offset 339 inside a real MP3's ID3v2 tag, and the buggy shortcut
 * accepted it as a match anywhere in the search window rather than only at offset 0.
 * Fixed by always validating position against the offset window, whether or not
 * fragments exist - when there are none, the anchor's own position IS the thing
 * being validated. This fix alone eliminated both false positives on the test MP3
 * files, and improved the EOF-direction code's already-correct symmetric behavior.
 *
 * This does not mean false positives are now impossible - the fragment-chain
 * verification is still greedy/single-candidate (see below), which is a real,
 * remaining simplification relative to DROID's actual multi-candidate exploration.
 * But the specific cases found so far were traceable to this one concrete bug, not
 * to some irreducible gap versus DROID's real rigor.
 */
public class DroidSignatureVerifier {

    // ------------------------------------------------------------------
    // Pattern model: a byte pattern is a list of PatternElements, each of
    // which is either a literal byte, an inclusive byte range, a negated
    // single byte, or a negated multi-byte sequence.
    // ------------------------------------------------------------------

    enum Kind { LITERAL, RANGE, NOT, NOT_SEQUENCE }

    static final class PatternElement {
        final Kind kind;
        final int a; // literal value, or single-byte NOT value, or range low
        final int b; // range high (only used for RANGE)
        final byte[] notSequence; // only used for NOT_SEQUENCE (multi-byte negation)

        private PatternElement(Kind kind, int a, int b, byte[] notSequence) {
            this.kind = kind;
            this.a = a;
            this.b = b;
            this.notSequence = notSequence;
        }

        static PatternElement literal(int value) { return new PatternElement(Kind.LITERAL, value, -1, null); }
        static PatternElement not(int value) { return new PatternElement(Kind.NOT, value, -1, null); }
        static PatternElement range(int low, int high) { return new PatternElement(Kind.RANGE, low, high, null); }
        static PatternElement notSequence(byte[] forbidden) { return new PatternElement(Kind.NOT_SEQUENCE, -1, -1, forbidden); }

        int length() {
            return (kind == Kind.NOT_SEQUENCE) ? notSequence.length : 1;
        }

        boolean matchesAt(byte[] data, int pos) {
            switch (kind) {
                case LITERAL: return (data[pos] & 0xFF) == a;
                case NOT: return (data[pos] & 0xFF) != a;
                case RANGE: {
                    int v = data[pos] & 0xFF;
                    return v >= a && v <= b;
                }
                case NOT_SEQUENCE: {
                    for (int i = 0; i < notSequence.length; i++) {
                        if ((data[pos + i] & 0xFF) != (notSequence[i] & 0xFF)) return true;
                    }
                    return false;
                }
                default: throw new AssertionError();
            }
        }
    }

    static int patternLength(List<PatternElement> pattern) {
        int total = 0;
        for (PatternElement e : pattern) total += e.length();
        return total;
    }

    static List<PatternElement> parsePattern(String text) {
        List<PatternElement> out = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '[') {
                int close = text.indexOf(']', i);
                String inner = text.substring(i + 1, close);
                if (inner.startsWith("!")) {
                    String hex = inner.substring(1);
                    if (hex.length() == 2) {
                        out.add(PatternElement.not(hexByte(hex)));
                    } else {
                        out.add(PatternElement.notSequence(hexBytes(hex)));
                    }
                } else {
                    String[] parts = inner.split(":");
                    out.add(PatternElement.range(hexByte(parts[0]), hexByte(parts[1])));
                }
                i = close + 1;
            } else {
                out.add(PatternElement.literal(hexByte(text.substring(i, i + 2))));
                i += 2;
            }
        }
        return out;
    }

    static int hexByte(String twoHexChars) {
        return Integer.parseInt(twoHexChars, 16);
    }

    static byte[] hexBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /**
     * Endianness="Little-endian" handling: a NO-OP.
     *
     * An earlier version of this class reversed anchor/fragment byte order for any
     * "Little-endian" ByteSequence, on the assumption that multi-byte values needed
     * flipping to match real file bytes. Direct testing against a real MP3 file
     * disproved this: signature 266's EOF-anchored SubSequence (Little-endian,
     * anchor "FFFB") is meant to match the literal, UNREVERSED bytes 0xFF 0xFB -
     * exactly what's present in a real file's trailing audio frames. Reversing it
     * broke a genuine, real-DROID-confirmed match (PUID fmt/134) entirely.
     *
     * Reversal is therefore a no-op here. This does not mean Endianness carries no
     * meaning in DROID's real semantics - only that "reverse the whole anchor byte
     * sequence" is empirically wrong, and applying that guess was actively harmful.
     * Treat this gap as UNRESOLVED, not fixed.
     */
    static List<PatternElement> reversed(List<PatternElement> pattern) {
        return pattern;
    }

    // ------------------------------------------------------------------
    // Signature structure model
    // ------------------------------------------------------------------

    static final class Fragment {
        final int position;
        final int minOffset;
        final int maxOffset;
        final List<PatternElement> pattern;
        Fragment(int position, int minOffset, int maxOffset, List<PatternElement> pattern) {
            this.position = position;
            this.minOffset = minOffset;
            this.maxOffset = maxOffset;
            this.pattern = pattern;
        }
    }

    static final class SubSequenceDef {
        int position;
        List<PatternElement> anchor;
        int minSeqOffset;
        int maxSeqOffset; // -1 means "attribute absent" = unbounded
        final List<Fragment> leftFragments = new ArrayList<>();
        final List<Fragment> rightFragments = new ArrayList<>();
    }

    enum Reference { BOF, EOF, ANYWHERE }

    static final class ByteSequenceDef {
        Reference reference = Reference.BOF;
        boolean littleEndian = false;
        final List<SubSequenceDef> subSequences = new ArrayList<>();
    }

    static final class InternalSignatureDef {
        final int id;
        final List<ByteSequenceDef> byteSequences = new ArrayList<>();
        InternalSignatureDef(int id) { this.id = id; }
    }

    // ------------------------------------------------------------------
    // Low-level pattern search helpers
    // ------------------------------------------------------------------

    static boolean matchesAt(byte[] data, int start, List<PatternElement> pattern) {
        if (start < 0) return false;
        int pos = start;
        for (PatternElement e : pattern) {
            if (pos + e.length() > data.length) return false;
            if (!e.matchesAt(data, pos)) return false;
            pos += e.length();
        }
        return true;
    }

    /** First position in [fromInclusive, toInclusive] where pattern matches, or -1.
     *  Searches in increasing offset order (i.e. "leftmost/earliest first"). */
    static int findFirstForward(byte[] data, int fromInclusive, int toInclusive, List<PatternElement> pattern) {
        int lo = Math.max(0, fromInclusive);
        int hi = Math.min(toInclusive, data.length - patternLength(pattern));
        for (int start = lo; start <= hi; start++) {
            if (matchesAt(data, start, pattern)) return start;
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // Fragment chain verification (greedy / first-match-wins across POSITION STEPS -
    // see class javadoc for why this is a deliberate simplification of DROID's real,
    // more thorough, but combinatorially-dangerous multi-alternative exploration).
    //
    // BUG FIX (found via signature 128 / MS Word, confirmed again on a real MP4 file
    // and signature 278): multiple Fragment elements can share the SAME Position
    // number, meaning "match ANY ONE of these alternatives here" (OR), not "match
    // all of these one after another" (AND/sequential). An earlier version of this
    // class sorted all fragments by position and walked them as one flat sequential
    // chain, so same-Position fragments were wrongly treated as separate mandatory
    // steps - e.g. signature 278 (MP4) requires ONE of "iso2"/"isom"/"mp41"/"mp42"
    // (all Position=1) after "ftyp", but the old code tried "iso2" first, found it
    // absent, and failed the WHOLE chain without ever trying "isom" or "mp42" - even
    // though the real file plainly contained "mp42" right where expected. Fixed by
    // grouping fragments by Position first, then treating each group as a set of
    // alternatives: the step succeeds if ANY pattern in the group matches within the
    // shared offset window, and the cursor advances by whichever one actually
    // matched (earliest match position wins if more than one alternative fits).
    // ------------------------------------------------------------------

    /** Groups fragments by Position (ascending), preserving each group's members in
     *  their original (file) order - used so same-Position fragments can be tried
     *  as alternatives rather than sequential steps. */
    static List<List<Fragment>> groupByPosition(List<Fragment> fragments) {
        TreeMap<Integer, List<Fragment>> grouped = new TreeMap<>();
        for (Fragment f : fragments) {
            grouped.computeIfAbsent(f.position, k -> new ArrayList<>()).add(f);
        }
        return new ArrayList<>(grouped.values());
    }

    /** Verifies the RightFragment chain starting right after an anchor match.
     *  Returns the cursor position immediately after the whole chain (i.e. one past
     *  the last matched fragment byte), or -1 if any step in the chain fails. Each
     *  step is a group of same-Position fragments; the step succeeds if ANY ONE of
     *  them matches (see BUG FIX note above). */
    static int verifyRightFragmentChain(byte[] data, int afterAnchor, List<Fragment> rightFragments) {
        int cursor = afterAnchor;
        for (List<Fragment> alternatives : groupByPosition(rightFragments)) {
            int bestFound = -1;
            int bestPatternLen = -1;
            for (Fragment f : alternatives) {
                int from = cursor + f.minOffset;
                int to = cursor + f.maxOffset;
                int found = findFirstForward(data, from, to, f.pattern);
                if (found >= 0 && (bestFound < 0 || found < bestFound)) {
                    bestFound = found;
                    bestPatternLen = patternLength(f.pattern);
                }
            }
            if (bestFound < 0) return -1;
            cursor = bestFound + bestPatternLen;
        }
        return cursor;
    }

    /** Verifies the LeftFragment chain ending right before an anchor match.
     *  Returns the cursor position at the start of the whole chain (i.e. the
     *  earliest matched fragment byte), or -1 if any step in the chain fails. Same
     *  same-Position-is-OR handling as verifyRightFragmentChain (see above). */
    static int verifyLeftFragmentChain(byte[] data, int beforeAnchor, List<Fragment> leftFragments) {
        int cursor = beforeAnchor;
        for (List<Fragment> alternatives : groupByPosition(leftFragments)) {
            int bestFound = -1;
            for (Fragment f : alternatives) {
                int patLen = patternLength(f.pattern);
                int to = cursor - f.minOffset - patLen;
                int from = cursor - f.maxOffset - patLen;
                int found = findFirstForward(data, from, to, f.pattern);
                // "Earliest" for a left fragment means closest to the anchor, i.e.
                // the LARGEST found offset (rightmost within its window).
                if (found >= 0 && found > bestFound) {
                    bestFound = found;
                }
            }
            if (bestFound < 0) return -1;
            cursor = bestFound;
        }
        return cursor;
    }

    // ------------------------------------------------------------------
    // SubSequence matching
    // ------------------------------------------------------------------

    /**
     * Matches a CHAINED (non-first) SubSequence: its anchor must simply be found
     * within [cursor+minSeqOffset, cursor+maxSeqOffset] of wherever the previous
     * SubSequence in the chain ended. No reference-point endpoint validity check
     * applies here (matching real DROID: that check only gates the SubSequence
     * that's directly anchored to the ByteSequence's BOF/EOF reference point).
     * Returns the new cursor (end of this SubSequence's full match), or -1.
     */
    static int matchChainedSubSequence(byte[] data, SubSequenceDef sub, int cursor) {
        if (sub.anchor == null) return -1;
        int from = cursor + sub.minSeqOffset;
        int to = (sub.maxSeqOffset >= 0) ? (cursor + sub.maxSeqOffset) : data.length;
        int anchorPos = findFirstForward(data, from, to, sub.anchor);
        if (anchorPos < 0) return -1;
        int afterAnchor = anchorPos + patternLength(sub.anchor);
        int afterRight = verifyRightFragmentChain(data, afterAnchor, sub.rightFragments);
        if (afterRight < 0) return -1;
        int beforeLeft = verifyLeftFragmentChain(data, anchorPos, sub.leftFragments);
        if (beforeLeft < 0) return -1;
        return afterRight;
    }

    /**
     * Safety cap on how far back/forward the FIXED SubSequence anchor search will
     * look, in bytes, from the reference point. Without this, a non-matching
     * signature causes a naive byte-by-byte scan of the ENTIRE remaining file (since
     * the corrected offset semantics - see class javadoc - no longer bound the
     * anchor's own search window). Real DROID has an analogous "maxBytesToScan"
     * safety cap for the same reason. This value is a pragmatic, undocumented choice
     * (not taken from DROID's real default) - large enough to comfortably cover the
     * ID3v2-tag case that motivated this rewrite (real tags seen so far: ~2.5KB and
     * ~259KB), small enough to keep a full 2,018-signature scan of a large file
     * from effectively hanging on every non-matching signature.
     */
    static int MAX_ANCHOR_SEARCH_DISTANCE = 3000; //Cases where 3000 is required has been found for large mp4 files.

    /**
     * Matches the FIXED SubSequence of a ByteSequence - the one directly anchored to
     * the reference point (BOF or EOF). This is where the corrected offset semantics
     * apply: minSeqOffset/maxSeqOffset bound where the fragment chain's END lands
     * relative to referencePosition, NOT where the anchor itself is found. The anchor
     * search tries candidates working outward from the reference point until one
     * produces a validly-placed chain (see class javadoc for the real-source basis
     * of this correction) - bounded by MAX_ANCHOR_SEARCH_DISTANCE (see its javadoc).
     *
     * @param referencePosition for EOF: typically data.length (one past the last
     *                          byte); for BOF: typically 0.
     * @param searchBackward    true for EOF (search anchor candidates going backward
     *                          from referencePosition), false for BOF (forward).
     * @return the new cursor (end of this SubSequence's full match, for chaining to
     *         the next SubSequence, if any), or -1 if no valid candidate exists.
     */
    static int matchFixedSubSequence(byte[] data, SubSequenceDef sub, int referencePosition, boolean searchBackward) {
        if (sub.anchor == null) return -1;
        int anchorLen = patternLength(sub.anchor);
        int maxOffset = (sub.maxSeqOffset >= 0) ? sub.maxSeqOffset : Integer.MAX_VALUE / 2;
        int minOffset = sub.minSeqOffset;

        if (searchBackward) {
            // EOF-anchored: try anchor candidates starting close to the reference
            // point and moving further back (away from EOF) until the RIGHT fragment
            // chain's end satisfies [referencePosition-maxOffset, referencePosition-minOffset].
            int candidate = referencePosition - anchorLen; // closest possible to reference point
            int floor = Math.max(0, referencePosition - MAX_ANCHOR_SEARCH_DISTANCE);
            while (candidate >= floor) {
                if (matchesAt(data, candidate, sub.anchor)) {
                    int afterAnchor = candidate + anchorLen;
                    int chainEnd = verifyRightFragmentChain(data, afterAnchor, sub.rightFragments);
                    if (chainEnd >= 0) {
                        int distanceFromReference = referencePosition - chainEnd;
                        if (distanceFromReference >= minOffset && distanceFromReference <= maxOffset) {
                            int beforeLeft = verifyLeftFragmentChain(data, candidate, sub.leftFragments);
                            if (beforeLeft >= 0) {
                                return chainEnd;
                            }
                        }
                    }
                }
                candidate--;
            }
            return -1;
        } else {
            // BOF-anchored: try anchor candidates starting close to the reference
            // point and moving forward until the LEFT fragment chain's start
            // satisfies [referencePosition+minOffset, referencePosition+maxOffset].
            // (Symmetric to the EOF case above; see real source's forward branch.)
            int candidate = referencePosition; // closest possible to reference point
            int ceiling = Math.min(data.length - anchorLen, referencePosition + MAX_ANCHOR_SEARCH_DISTANCE);
            while (candidate <= ceiling) {
                if (matchesAt(data, candidate, sub.anchor)) {
                    int beforeLeft = verifyLeftFragmentChain(data, candidate, sub.leftFragments);
                    if (beforeLeft >= 0) {
                        // BUG FIX: previously this check was skipped entirely when there were
                        // no left fragments ("sub.leftFragments.isEmpty() || ..."), on the wrong
                        // assumption that "nothing to check" meant "no offset constraint at all".
                        // That let signature 10 (TIFF, anchor "4D4D002A", SubSeqMinOffset=0,
                        // SubSeqMaxOffset=0, NO fragments) match a byte-4 coincidence anywhere
                        // within MAX_ANCHOR_SEARCH_DISTANCE, instead of requiring it AT byte 0
                        // exactly - found via a real MP3 file where those 4 bytes happened to
                        // occur at offset 339, inside an ID3v2 tag, and were wrongly accepted.
                        // When there are no left fragments, verifyLeftFragmentChain returns
                        // `candidate` unchanged, so this check now correctly constrains the
                        // ANCHOR's own position directly - exactly what SubSeqMinOffset/
                        // SubSeqMaxOffset mean for a fragment-less SubSequence.
                        int distanceFromReference = beforeLeft - referencePosition;
                        boolean leftOk = distanceFromReference >= minOffset && distanceFromReference <= maxOffset;
                        if (leftOk) {
                            int afterAnchor = candidate + anchorLen;
                            int chainEnd = verifyRightFragmentChain(data, afterAnchor, sub.rightFragments);
                            if (chainEnd >= 0) {
                                return chainEnd;
                            }
                        }
                    }
                }
                candidate++;
            }
            return -1;
        }
    }

    /**
     * Verifies a whole ByteSequence: its SubSequences (there may be several,
     * chained) must ALL match, in Position order for BOF/Anywhere, or REVERSE
     * Position order for EOF (matching real DROID's ByteSequence.matches(), which
     * iterates seq.length-1 down to 0 for EOFoffset ByteSequences).
     */
    static boolean matchByteSequence(byte[] data, ByteSequenceDef bs) {
        List<SubSequenceDef> subs = new ArrayList<>(bs.subSequences);
        subs.sort(Comparator.comparingInt(s -> s.position));
        boolean backward = (bs.reference == Reference.EOF);
        if (backward) Collections.reverse(subs);

        int referencePosition = backward ? data.length : 0;
        boolean first = true;
        int cursor = -1;
        for (SubSequenceDef sub : subs) {
            int result = first
                    ? matchFixedSubSequence(data, sub, referencePosition, backward)
                    : matchChainedSubSequence(data, sub, cursor);
            if (result < 0) return false;
            cursor = result;
            first = false;
        }
        return true;
    }

    /** A signature matches only if ALL of its ByteSequences match. */
    static boolean matchSignature(byte[] data, InternalSignatureDef sig) {
        for (ByteSequenceDef bs : sig.byteSequences) {
            if (!matchByteSequence(data, bs)) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Debug tracing - traces one signature's matching attempt step by step.
    // ------------------------------------------------------------------

    static boolean matchByteSequenceDebug(byte[] data, ByteSequenceDef bs) {
        List<SubSequenceDef> subs = new ArrayList<>(bs.subSequences);
        subs.sort(Comparator.comparingInt(s -> s.position));
        boolean backward = (bs.reference == Reference.EOF);
        if (backward) Collections.reverse(subs);

        int referencePosition = backward ? data.length : 0;
        System.out.printf("    reference=%s referencePosition=%,d%n", bs.reference, referencePosition);

        boolean first = true;
        int cursor = -1;
        for (SubSequenceDef sub : subs) {
            System.out.printf("    SubSequence Position %d (%s): minSeqOffset=%d maxSeqOffset=%s%n",
                    sub.position, first ? "FIXED to reference point" : "chained",
                    sub.minSeqOffset, sub.maxSeqOffset >= 0 ? String.valueOf(sub.maxSeqOffset) : "unbounded");

            int result = first
                    ? matchFixedSubSequence(data, sub, referencePosition, backward)
                    : matchChainedSubSequence(data, sub, cursor);

            if (result < 0) {
                System.out.println("      -> NO valid candidate found. FAIL here.");
                return false;
            }
            System.out.printf("      -> matched, chain ends at %,d%n", result);
            cursor = result;
            first = false;
        }
        System.out.println("    -> ALL SubSequences in this ByteSequence matched.");
        return true;
    }

    // ------------------------------------------------------------------
    // XML parsing (StAX)
    // ------------------------------------------------------------------

    static List<InternalSignatureDef> parseSignatures(File xmlFile) throws Exception {
        List<InternalSignatureDef> signatures = new ArrayList<>();
        XMLInputFactory factory = XMLInputFactory.newInstance();
        try (InputStream in = new BufferedInputStream(new FileInputStream(xmlFile))) {
            XMLStreamReader r = factory.createXMLStreamReader(in);

            InternalSignatureDef currentSig = null;
            ByteSequenceDef currentByteSeq = null;
            SubSequenceDef currentSubSeq = null;

            boolean inSequenceTag = false;
            boolean inFragmentTag = false;
            String fragmentSide = null;
            int fragmentPosition = 0, fragmentMin = 0, fragmentMax = 0;
            StringBuilder text = new StringBuilder();

            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    String local = r.getLocalName();
                    switch (local) {
                        case "InternalSignature":
                            currentSig = new InternalSignatureDef(Integer.parseInt(r.getAttributeValue(null, "ID")));
                            signatures.add(currentSig);
                            break;
                        case "ByteSequence": {
                            currentByteSeq = new ByteSequenceDef();
                            String ref = r.getAttributeValue(null, "Reference");
                            if ("EOFoffset".equals(ref)) currentByteSeq.reference = Reference.EOF;
                            else if (ref == null || ref.isEmpty()) currentByteSeq.reference = Reference.ANYWHERE;
                            else currentByteSeq.reference = Reference.BOF;
                            String endian = r.getAttributeValue(null, "Endianness");
                            currentByteSeq.littleEndian = "Little-endian".equals(endian);
                            currentSig.byteSequences.add(currentByteSeq);
                            break;
                        }
                        case "SubSequence": {
                            currentSubSeq = new SubSequenceDef();
                            currentSubSeq.position = intAttr(r, "Position", 1);
                            currentSubSeq.minSeqOffset = intAttr(r, "SubSeqMinOffset", 0);
                            currentSubSeq.maxSeqOffset = intAttr(r, "SubSeqMaxOffset", -1);
                            currentByteSeq.subSequences.add(currentSubSeq);
                            break;
                        }
                        case "Sequence":
                            inSequenceTag = true;
                            text.setLength(0);
                            break;
                        case "LeftFragment":
                        case "RightFragment":
                            inFragmentTag = true;
                            fragmentSide = local.startsWith("Left") ? "Left" : "Right";
                            fragmentPosition = intAttr(r, "Position", 0);
                            fragmentMin = intAttr(r, "MinOffset", 0);
                            fragmentMax = intAttr(r, "MaxOffset", 0);
                            text.setLength(0);
                            break;
                        default:
                            break;
                    }
                } else if (ev == XMLStreamConstants.CHARACTERS && (inSequenceTag || inFragmentTag)) {
                    text.append(r.getText());
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    String local = r.getLocalName();
                    if (local.equals("Sequence") && inSequenceTag) {
                        inSequenceTag = false;
                        try {
                            List<PatternElement> pattern = parsePattern(text.toString().trim());
                            if (currentByteSeq.littleEndian) pattern = reversed(pattern);
                            currentSubSeq.anchor = pattern;
                        } catch (RuntimeException parseError) {
                            System.err.println("  [skip] Signature " + currentSig.id
                                    + ": could not parse anchor \"" + text + "\" (" + parseError + ")");
                        }
                    } else if ((local.equals("LeftFragment") || local.equals("RightFragment")) && inFragmentTag) {
                        inFragmentTag = false;
                        try {
                            List<PatternElement> pattern = parsePattern(text.toString().trim());
                            if (currentByteSeq.littleEndian) pattern = reversed(pattern);
                            Fragment f = new Fragment(fragmentPosition, fragmentMin, fragmentMax, pattern);
                            if (fragmentSide.equals("Left")) currentSubSeq.leftFragments.add(f);
                            else currentSubSeq.rightFragments.add(f);
                        } catch (RuntimeException parseError) {
                            System.err.println("  [skip] Signature " + currentSig.id
                                    + ": could not parse " + fragmentSide + "Fragment \"" + text
                                    + "\" (" + parseError + ") - signature will not match via this SubSequence");
                            currentSubSeq.anchor = null;
                        }
                    }
                }
            }
            r.close();
        }
        return signatures;
    }

    static int intAttr(XMLStreamReader r, String name, int defaultValue) {
        String v = r.getAttributeValue(null, name);
        return (v == null || v.isEmpty()) ? defaultValue : Integer.parseInt(v);
    }

    // ------------------------------------------------------------------
    // FileFormat (PUID) lookup
    // ------------------------------------------------------------------

    static final class FileFormatDef {
        final int id;
        final String puid;
        final String name;
        final String mimeType; // may be null - not every FileFormat entry has one
        final List<Integer> signatureIds;
        final List<Integer> hasPriorityOverFormatIds;
        FileFormatDef(int id, String puid, String name, String mimeType,
                      List<Integer> signatureIds, List<Integer> hasPriorityOverFormatIds) {
            this.id = id;
            this.puid = puid;
            this.name = name;
            this.mimeType = mimeType;
            this.signatureIds = signatureIds;
            this.hasPriorityOverFormatIds = hasPriorityOverFormatIds;
        }
    }

    static List<FileFormatDef> parseFileFormats(File xmlFile) throws Exception {
        List<FileFormatDef> formats = new ArrayList<>();
        XMLInputFactory factory = XMLInputFactory.newInstance();
        try (InputStream in = new BufferedInputStream(new FileInputStream(xmlFile))) {
            XMLStreamReader r = factory.createXMLStreamReader(in);
            int id = -1;
            String puid = null, name = null, mimeType = null;
            List<Integer> sigIds = null;
            List<Integer> priorityOverIds = null;
            boolean inFileFormat = false;
            boolean inSigIdTag = false;
            boolean inPriorityTag = false;
            StringBuilder text = new StringBuilder();

            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    String local = r.getLocalName();
                    if (local.equals("FileFormat")) {
                        inFileFormat = true;
                        id = Integer.parseInt(r.getAttributeValue(null, "ID"));
                        puid = r.getAttributeValue(null, "PUID");
                        name = r.getAttributeValue(null, "Name");
                        mimeType = r.getAttributeValue(null, "MIMEType"); // may be null - not always present
                        sigIds = new ArrayList<>();
                        priorityOverIds = new ArrayList<>();
                    } else if (local.equals("InternalSignatureID") && inFileFormat) {
                        inSigIdTag = true;
                        text.setLength(0);
                    } else if (local.equals("HasPriorityOverFileFormatID") && inFileFormat) {
                        inPriorityTag = true;
                        text.setLength(0);
                    }
                } else if (ev == XMLStreamConstants.CHARACTERS && (inSigIdTag || inPriorityTag)) {
                    text.append(r.getText());
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    String local = r.getLocalName();
                    if (local.equals("InternalSignatureID")) {
                        inSigIdTag = false;
                        sigIds.add(Integer.parseInt(text.toString().trim()));
                    } else if (local.equals("HasPriorityOverFileFormatID")) {
                        inPriorityTag = false;
                        priorityOverIds.add(Integer.parseInt(text.toString().trim()));
                    } else if (local.equals("FileFormat")) {
                        inFileFormat = false;
                        formats.add(new FileFormatDef(id, puid, name, mimeType, sigIds, priorityOverIds));
                    }
                }
            }
            r.close();
        }
        return formats;
    }

    /**
     * Applies DROID's priority-resolution rule: if FileFormat A "HasPriorityOverFileFormatID"
     * pointing at FileFormat B, and BOTH matched, B is suppressed - A wins. This is a
     * real, separate stage in DROID's identification pipeline, distinct from raw binary
     * signature matching (see class javadoc) - discovered by noticing this element on
     * real FileFormat entries (e.g. ODP's fmt/1754) and finding it necessary to explain
     * why real DROID reports one PUID even when several raw signatures may have fired.
     *
     * Iterates to a fixpoint in case priority relationships chain (A over B over C).
     */
    static List<FileFormatDef> applyPriorityResolution(List<FileFormatDef> matchedFormats) {
        Set<Integer> survivingIds = new HashSet<>();
        for (FileFormatDef f : matchedFormats) survivingIds.add(f.id);

        Map<Integer, FileFormatDef> byId = new HashMap<>();
        for (FileFormatDef f : matchedFormats) byId.put(f.id, f);

        boolean changed = true;
        while (changed) {
            changed = false;
            for (FileFormatDef f : matchedFormats) {
                if (!survivingIds.contains(f.id)) continue;
                for (int suppressedId : f.hasPriorityOverFormatIds) {
                    if (survivingIds.remove(suppressedId)) {
                        changed = true;
                    }
                }
            }
        }

        List<FileFormatDef> result = new ArrayList<>();
        for (FileFormatDef f : matchedFormats) {
            if (survivingIds.contains(f.id)) result.add(f);
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Public instance API.
    //
    // Usage:
    //   DroidSignatureVerifier verifier = new DroidSignatureVerifier(new File("DROID_SignatureFile_V124.xml"));
    //   DetectionResult[] results = verifier.detect(new File("somefile.mp4"));
    //
    // The constructor parses the (potentially large, ~2,000+ signature) XML file
    // ONCE and keeps the parsed structures in instance fields, so a single
    // DroidSignatureVerifier instance can be reused to detect() many target files
    // without re-parsing the signature file every time - the parse step (a few
    // hundred ms to ~1.5s depending on file size) was previously repeated on every
    // single detection when this was a one-shot static method.
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // DetectionResult is now its own standalone top-level class (see
    // DetectionResult.java), shared with DroidSignatureAhoCorasickVerifier.
    // ------------------------------------------------------------------

    private final List<InternalSignatureDef> signatures;
    private final List<FileFormatDef> formats;

    /**
     * Parses the given DROID signature file once, keeping the resulting
     * signatures and file-format definitions in this instance for reuse across
     * any number of detect() calls.
     *
     * @param signatureFile the DROID_SignatureFile*.xml to parse
     */
    public DroidSignatureVerifier(File signatureFile) throws Exception {
        System.out.println("Parsing full signature structure (anchors + fragments + endianness): " + signatureFile);
        long t0 = System.nanoTime();
        this.signatures = parseSignatures(signatureFile);
        this.formats = parseFileFormats(signatureFile);
        long t1 = System.nanoTime();
        System.out.printf("  Parsed %,d signatures and %,d file formats in %.1f ms%n",
                signatures.size(), formats.size(), (t1 - t0) / 1e6);
    }

    /**
     * Runs full format detection for one file, using the signature file this
     * instance was constructed with, and returns up to 10 candidate results,
     * best guess first.
     *
     * "Best guess first" means: results surviving priority resolution
     * (applyPriorityResolution - see its javadoc for what that means and its
     * known limits) are listed first, since those are DROID's real mechanism for
     * picking a winner among competing raw matches. Any additional raw matches
     * that got suppressed by priority resolution are appended after, as lower-
     * confidence "also matched, but a higher-priority format won" candidates -
     * NOT a confidence-ranked list beyond the resolved/suppressed split, since
     * this class has no further ranking signal (DROID's own reports typically
     * expect at most one winner; showing up to 10 here is a deliberate widening
     * for exploratory/debugging use, not something DROID itself does).
     *
     * Retains the existing System.out progress/diagnostic logging from the
     * original main() (verification time, raw matched IDs) - this is intentional
     * for now per request; a future version may want a quiet mode.
     *
     * @param targetFile the file to identify
     * @return array of 0 to 10 DetectionResults, index 0 = top candidate
     */
    public DetectionResult[] detect(File targetFile) throws Exception {
        byte[] data = Files.readAllBytes(targetFile.toPath());
        System.out.println("\nVerifying against: " + targetFile + " (" + data.length + " bytes)");
        return detectFromBytes(data);
    }

    /**
     * Same as detect(File), but reads from an InputStream instead - e.g. for use
     * inside a pipeline (like warc-indexer's own WARCPayloadAnalyzers, which is
     * where this whole investigation started) where the payload is already
     * available as a stream rather than a standalone file on disk.
     *
     * If the stream supports mark/reset (InputStream.markSupported()), this
     * attempts stream.reset() before reading, on the assumption that an earlier
     * step (e.g. Tika's own MIME/format detection, which typically peeks at the
     * first few KB via mark/reset and is expected to leave the stream positioned
     * back at the start afterward) may have already consumed some of the stream.
     * If the stream doesn't support mark/reset, this reads from wherever the
     * stream's current position happens to be - there is no general way to
     * "rewind" an arbitrary InputStream that never had mark() called on it, so
     * the caller is responsible for supplying a stream positioned where they want
     * reading to start if mark/reset isn't available (e.g. TikaInputStream, which
     * supports mark() with an effectively unlimited read limit, is a good fit for
     * this method's needs in a Tika-based pipeline).
     *
     * Reads the ENTIRE remaining stream into memory (same as detect(File) reads
     * the entire file) - full binary content is needed regardless, since
     * EOF-anchored signatures (see signature 266, the MP3 case) need to examine
     * bytes near the true end of the content, not just a prefix.
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
                // No prior mark() was set, or the stream doesn't actually support
                // resetting despite markSupported() returning true - proceed from
                // wherever the stream currently is rather than failing outright.
                System.out.println("  (reset() failed on the supplied stream, reading from current position: " + e + ")");
            }
        }
        byte[] data = in.readAllBytes();
        System.out.println("\nVerifying against: <input stream> (" + data.length + " bytes)");
        return detectFromBytes(data);
    }

    /**
     * The actual detection logic, shared by both detect(File) and
     * detect(InputStream) - everything downstream of "we now have the full
     * content as a byte[]" lives here exactly once.
     */
    private DetectionResult[] detectFromBytes(byte[] data) throws Exception {
        long t2 = System.nanoTime();
        List<Integer> matchedSignatureIds = new ArrayList<>();
        for (InternalSignatureDef sig : signatures) {
            if (matchSignature(data, sig)) {
                matchedSignatureIds.add(sig.id);
            }
        }
        long t3 = System.nanoTime();
        System.out.printf("  Verification of all %,d signatures completed in %.1f ms (no hang, no backtracking)%n",
                signatures.size(), (t3 - t2) / 1e6);

        if (matchedSignatureIds.isEmpty()) {
            System.out.println("  No signatures matched.");
            return new DetectionResult[0];
        }

        List<FileFormatDef> matchedFormats = new ArrayList<>();
        for (FileFormatDef fmt : formats) {
            for (int sigId : fmt.signatureIds) {
                if (matchedSignatureIds.contains(sigId)) {
                    matchedFormats.add(fmt);
                    break;
                }
            }
        }
        System.out.println("  Raw matched signature IDs: " + matchedSignatureIds
                + " (" + matchedFormats.size() + " format(s) before priority resolution)");

        List<FileFormatDef> resolved = applyPriorityResolution(matchedFormats);
        System.out.println("  After priority resolution:");
        for (FileFormatDef fmt : resolved) {
            System.out.println("    -> " + fmt.puid + "  " + fmt.name);
        }

        // Build the final ordered list: priority-resolution survivors first (best
        // guess), then any suppressed raw matches after, capped at 10 total.
        List<FileFormatDef> ordered = new ArrayList<>(resolved);
        for (FileFormatDef fmt : matchedFormats) {
            if (!resolved.contains(fmt)) {
                ordered.add(fmt);
            }
        }

        int count = Math.min(10, ordered.size());
        DetectionResult[] results = new DetectionResult[count];
        for (int i = 0; i < count; i++) {
            FileFormatDef fmt = ordered.get(i);
            results[i] = new DetectionResult(fmt.puid, fmt.name, fmt.mimeType);
        }
        return results;
    }

    // ------------------------------------------------------------------
    // Demo driver - hardcoded variables for IDE "Run" usage, no CLI args.
    // Set debugSignatureId to trace one signature step by step; leave null
    // for a normal full scan against all signatures.
    // ------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        String signatureFile = "/home/teg/eclipse-workspace/droid-light/src/main/resources/DROID_SignatureFile_V124.xml";
//        String scanFile = "/home/teg/Desktop/Evald_Tang_Kristensen_samling-oversigt.pdf";
//        String scanFile = "/home/teg/Music/SOTB/02-Tim-Wright-CoLD-SToRAGE-Castle-burger-bar-mix.mp3";
        String scanFile = "/home/teg/Downloads/Bozzy_and_the_Bint_-_BBKB_20171129.mp3";

        File sigFile = new File(signatureFile);
        File targetFile = new File(scanFile);
           
        DroidSignatureVerifier droid = new DroidSignatureVerifier(sigFile);
        DetectionResult[] results = droid.detect(targetFile);
        System.out.println(results[0]);

    }
}