# droid-light

droid-light is a small Java library that identifies file formats from the "magic bytes" inside a file, using the signature file of the [PRONOM](https://www.nationalarchives.gov.uk/PRONOM/) project, the file-format registry of The National Archives (UK) that [DROID](https://github.com/digital-preservation/droid) is built on.

Give it a file or a stream and it tells you which format it is: the PRONOM identifier (PUID), the format name, the MIME type and the version.

```java
// Create once - reading the signature file takes a second or two - and reuse it, also from many threads.
DroidSignatureVerifier verifier = new DroidSignatureVerifier(new File("DROID_SignatureFile_V124.xml"));

DetectionResult[] results = verifier.detect(new File("somefile.pdf"));   // or verifier.detect(inputStream)
if (results.length > 0) {
    System.out.println(results[0]);  // fmt/18  Acrobat PDF 1.4 - Portable Document Format  [application/pdf; version=1.4]
}
```

## Why this library exists

DROID checks a signature that consists of several byte patterns separated by flexible gaps with nested loops that try every possible position of every pattern. For some files the number of combinations explodes, and DROID keeps running at 100% CPU for practically forever. This was reported to the DROID project: [DROID hangs indefinitely (100% CPU) on WebP image](https://github.com/digital-preservation/droid/issues/1663). A tool that identifies files unattended, in large numbers, cannot afford a single file that never finishes.

droid-light reads the same signature file and applies the same matching rules for binary signatures, but it never tries all combinations: every pattern is searched for in a limited window, and the first fitting position is used for the next pattern in the chain. The work per file is therefore bounded, whatever the content.

## The result

`detect()` returns up to 10 candidates, **index 0 being the best match**. Formats that DROID's priority rules prefer come first; an empty array means that no signature matched.

A `DetectionResult` holds the PUID (`getCode()`), the format name (`getText()`), the MIME type and the version, all taken from the signature file. There are three ways to get the MIME type:

| Method | Returns |
|---|---|
| `getPrimaryMimeTypeWithVersion()` | e.g. `"text/html; version=5"`. **`null` when the signature file has no MIME type for the format**, which is the case for about two thirds of the formats that have a signature (AVIF, for example). |
| `getPrimaryMimeType()` | the same without the version. When the signature file lists several MIME types, the first one. |
| `getPrimaryMimeTypeWithVersionOrName()` | **never null**: the MIME type with version when there is one, otherwise the format name, e.g. `"AV1 Image File Format"`. Use it when a value is always needed, for example in a report or a search index. A returned name is not a valid MIME type. |

## Files, streams and large files

- `detect(File)` and `detect(InputStream)` do the same. A stream is not closed. If it supports `mark()`/`reset()` it is reset to its start before reading; otherwise reading starts at its current position.
- Files up to 100 MB are read completely. Larger files, and streams longer than 40 MB, are examined through a window of 20 MB at the start and 20 MB at the end, because signatures look at the beginning and the end of a file. Memory use stays small and predictable, from a few bytes up to many gigabytes. A stream is still read to its end, because a stream cannot skip ahead.

## Faster when you know the MIME type

If you already know the MIME type, for example from an HTTP `Content-Type` header, you can check only the signatures of the formats that declare that type (between 1 and about 50 signatures instead of all of them). Such a header can be wrong, so always fall back to the full scan:

```java
DetectionResult[] r = new DetectionResult[0];
if (verifier.getSignatureCountForMimeType(contentType) > 0) {
    r = verifier.detectCommonMimeTypes(stream, contentType);   // an empty array if nothing matched
}
if (r.length == 0) {
    r = verifier.detect(stream);                               // the full scan
}
```

`getSignatureCountForMimeType()` returns 0 for types without any binary signature (CSS and JavaScript, for example) and for unknown types; skipping the shortcut then saves reading the stream twice.

## The two detector classes

There are two classes with the same purpose. Both read the same signature file, apply the same rules and give the same answer for ordinary files.

| | `DroidSignatureVerifier` | `DroidSignatureAhoCorasickVerifier` |
|---|---|---|
| How it searches | checks the signatures one after another, each in a small window | looks for the start patterns of all signatures in one pass ([Aho-Corasick](https://en.wikipedia.org/wiki/Aho%E2%80%93Corasick_algorithm)), then checks the candidates |
| Created from | a `File` or an `InputStream` | a `File` |
| `detect(File)`, `detect(InputStream)` | yes | yes |
| `detectCommonMimeTypes()` | yes | no |

Use `DroidSignatureVerifier` unless you have measured that the other one is faster for your data; which one is faster depends on the kind of files. They can differ only in rare cases: a signature that has no distance limit at all in the signature file (a marker that may occur anywhere) is searched with a different limit by each class.

## The signature file

The signatures come from PRONOM. `src/main/resources/DROID_SignatureFile_V124.xml` is PRONOM's DROID signature file V124 (2,258 signatures) plus two signatures that PRONOM does not have yet, 2,260 in total:

- **EOT font, version 1.2**
- **SVG without an XML declaration**: the PRONOM signatures for SVG require `<?xml version="1.0"` at the very start of the file, so SVG files that begin directly with `<svg` were not recognised. The added signature maps them to the generic SVG format `fmt/91`.

New releases of the signature file are published by PRONOM on the [DROID signature files page](https://www.nationalarchives.gov.uk/aboutapps/pronom/droid-signature-files.htm). To use another release, load it with the `File` or `InputStream` constructor.

## Limits

- **No guessing.** Only binary signatures are used, never file extensions. Formats without a binary signature (plain text, CSS, JavaScript and others) are not identified.
- **No container signatures.** DROID also looks inside ZIP and OLE2 containers to tell, for example, a Word document from a spreadsheet. droid-light does not; such files are reported by their binary signature only.
- **Damaged or truncated files** are not identified when the signature needs something at the end of the file (the `%%EOF` of a PDF, the end of a PNG, the end marker of a JPEG). An empty result is the correct answer then.
- **Search distance.** A pattern is looked for within the first or last 3,000 bytes of the file. Signatures that allow a larger distance in the signature file are searched that far; the end marker of a JPEG, for example, may be followed by up to 131,072 bytes of other data.

## Building

Java 11 or later: `mvn clean install`. The signature file is bundled in the jar.

## License

BSD 3-Clause, see [LICENSE](LICENSE). DROID is released under the New BSD License as well. droid-light is an independent implementation that reads the same signature file; it is not affiliated with or endorsed by The National Archives.