package dk.kb.droidlight;

/**
 * DetectionResult - one candidate format detection result: PUID ("code") and
 * human-readable format name ("text"), plus MIME type where the signature file
 * provides one (not every FileFormat entry does).
 *
 * Example: code="fmt/18", text="Acrobat PDF 1.4 - Portable Document Format",
 * mimeType="application/pdf".
 *
 * Shared by both DroidSignatureVerifier and DroidSignatureAhoCorasickVerifier -
 * pulled out to its own top-level class since both classes need to construct
 * and return arrays of this type, and having one of them own it as a nested
 * class meant the other had to reach into it via a slightly awkward
 * "DroidSignatureVerifier.DetectionResult" qualifier for no real reason - the
 * two verifier classes are peers (same detect(File)/detect(InputStream)
 * contract), not one depending on the other's internals.
 */
public final class DetectionResult {
    public final String code; // PUID, e.g. "fmt/18"
    public final String text; // format name, e.g. "Acrobat PDF 1.4 - Portable Document Format"
    public final String mimeType; // e.g. "application/pdf" - may be null, not every FileFormat entry has one

    public DetectionResult(String code, String text, String mimeType) {
        this.code = code;
        this.text = text;
        this.mimeType = mimeType;
    }

    @Override
    public String toString() {
        return code + "  " + text + (mimeType != null ? "  [" + mimeType + "]" : "");
    }
}