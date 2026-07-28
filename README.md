# droid-light

A faster, standalone reimplementation of [DROID](https://github.com/digital-preservation/droid)'s core binary
signature detection - built for scanning WARC files at scale with [warc-indexer](https://github.com/netarchivesuite/warc-indexer),
where a single pathological file should never be able to hang or crash a batch job.

It provides two interchangeable detector classes with the same public API, and a signature-file-driven
extension/MIME-type fallback detector.

## Usage

Both `DroidSignatureVerifier` and `DroidSignatureAhoCorasickVerifier` share the exact same shape:

```java
// Construct once - this parses the DROID signature file (a few hundred ms to ~1.5s,
// depending on its size). Reuse the same instance for every file/record you scan.
DroidSignatureVerifier verifier = new DroidSignatureVerifier(new File("DROID_SignatureFile_V124.xml"));
// or: DroidSignatureAhoCorasickVerifier verifier = new DroidSignatureAhoCorasickVerifier(sigFile);

// Then call detect() as many times as you like:
DetectionResult[] results = verifier.detect(new File("somefile.pdf"));

if (results.length > 0) {
    System.out.println(results[0]); // top candidate: "fmt/18  Acrobat PDF 1.4 - Portable Document Format  [application/pdf]"
}
```

`detect()` returns up to 10 candidate `DetectionResult`s, **index 0 being the best guess** - the result(s) that
survived DROID's own priority-resolution rules (`HasPriorityOverFileFormatID`). Any additional raw matches that
lost out to a higher-priority format are appended after, as lower-confidence "also matched" candidates. An empty
array means no binary signature matched.

Each `DetectionResult` carries `code` (PUID), `text` (format name), `mimeType`, and `version` - all read directly
from the signature file's `FileFormat` entry. For indexing into Solr as `content_type_droid` (matching real
DROID's own reporting format), use `getMimeTypeWithVersion()`:

```java
results[0].getMimeTypeWithVersion(); // "text/html; version=5", or "text/html" if there's no version
```

### File or InputStream - both are accepted

```java
DetectionResult[] r1 = verifier.detect(new File("/path/to/file.pdf"));

InputStream in = ...; // e.g. a WARC record's payload stream
DetectionResult[] r2 = verifier.detect(in);
```

`detect(InputStream)` does **not** close the stream - the caller retains ownership. If the stream supports
`mark()`/`reset()` (e.g. `TikaInputStream`, which `warc-indexer` already hands this code in practice), it is
reset to the start before reading, so it's safe to call after something else has already peeked at the stream.
If the stream doesn't support `mark`/`reset`, reading starts from wherever the stream's current position is.

Both entry points are safe on arbitrarily large input (multi-GB files/streams) without loading the whole thing
into memory - see [Large files](#large-files) below.

### Extension / MIME-type fallback

For content DROID can only ever identify by filename or by an HTTP `Content-Type` header - because it genuinely
has no binary signature at all (e.g. plain text files) - use `TentativeFormatDetector`. DROID's own internal
term for this is a **"tentative format"**: any signature-file entry with zero binary signatures.

```java
new TentativeFormatDetector(new File("DROID_SignatureFile_V124.xml")); // constructor loads the mapping once

FormatInfo byName = TentativeFormatDetector.detectFromFileName("/some/path/report.csv");
FormatInfo byMime = TentativeFormatDetector.detectFromMimeType("text/plain; charset=UTF-8");
```

This class is fully independent of the two `DetectFormat` classes above (separate signature-file parsing, no
shared code), and only ever returns a result for formats with **no** binary signature - it will never compete
with or override a real binary-signature match.

## The two detection algorithms

`DroidSignatureVerifier` and `DroidSignatureAhoCorasickVerifier` implement the **same** DROID signature semantics
(offset windows, fragment chains, endianness, multi-`ByteSequence` AND logic, priority resolution) and return
identical results on every file tested so far - they differ only in *how* they search for a signature's anchor
byte pattern, which is where their performance characteristics diverge.

### DroidSignatureVerifier - sequential per-signature search

For each of DROID's ~2,000+ signatures, independently: search a small window near the file's start or end
(bounded by `MAX_ANCHOR_SEARCH_DISTANCE`) for that signature's anchor byte pattern, then verify its fragment
chain. This is conceptually similar to what DROID itself does, minus the specific bug (an unbounded, combinatorial
fragment-matching search) that caused the original hang this project set out to fix.

Cost is roughly `signature_count x window_size` - **independent of file size**, since the window per signature
is small and fixed regardless of whether the file is 6 MB or 6 GB.

### DroidSignatureAhoCorasickVerifier - a single combined decision tree

Instead of 2,000+ independent searches, every signature's anchor pattern is compiled **once**, at construction
time, into a single combined [Aho-Corasick](https://en.wikipedia.org/wiki/Aho%E2%80%93Corasick_algorithm) trie -
a decision-tree automaton that finds candidate matches for **all** signatures simultaneously in one pass over
the input, rather than one pass per signature.

Two important details:

- **It scans the same bounded head/tail windows as `DroidSignatureVerifier`, not the whole file.** Since fragment
  verification can only ever succeed within those windows anyway (anything further away can't be checked), there
  is no benefit to scanning further - and doing so would cost real time on large files for no gain.
- **It reuses `DroidSignatureVerifier`'s own signature-file parser and fragment-verification methods directly**
  (`parseSignatures`, `verifyRightFragmentChain`, `verifyLeftFragmentChain`, `matchChainedSubSequence`,
  `readBoundedRegion`, `applyPriorityResolution`). Only the *anchor-finding* stage is different - the trie
  produces a short list of candidate positions per signature, and those candidates are then verified using
  exactly the same logic `DroidSignatureVerifier` uses. This is deliberate: a correctness fix made in one class's
  shared methods (e.g. the offset-semantics fix, or the DROID bitmask syntax fix) automatically applies to both
  detectors, with nothing to keep in sync by hand.

In practice this makes `DroidSignatureAhoCorasickVerifier` faster on small-to-medium files (one shared pass
beats many independent small ones), while `DroidSignatureVerifier` can be faster on very large files with
"noisy" repetitive content, where many short/common anchor byte patterns coincidentally recur often within the
scanned window. Both are bounded and safe either way - which one is faster for a given corpus is worth measuring
rather than assuming.

## Large files

Neither class loads a whole file into memory. Above a size threshold (`LARGE_FILE_THRESHOLD_BYTES`, default
100 MB), only a bounded **head** and **tail** window (`WINDOW_BYTES_FOR_LARGE_FILES`, default 20 MB each) are
read - via seeking for a `File`, or a streaming head-buffer-plus-circular-tail-buffer for an `InputStream` whose
total length isn't known in advance. This is what lets both classes run safely, with a small and predictable
memory footprint, against files from a few KB up to many GB, without ever risking an `OutOfMemoryError`.