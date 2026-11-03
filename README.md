# droid-light

droid-light is a small Java library that identifies file formats from the "magic bytes" inside a file, using the signature file of the [PRONOM](https://www.nationalarchives.gov.uk/PRONOM/) project, the file-format registry of The National Archives (UK) that [DROID](https://github.com/digital-preservation/droid) is built on.

Give it a file or a stream and it tells you which format it is: the PRONOM identifier (PUID), the format name, the MIME type and the version.

```java
// Create once - reading the signature file takes a second or two - and reuse it, also from many threads.
DroidSignatureVerifier verifier = new DroidSignatureVerifier(new File("DROID_SignatureFile_V125_experimental.xml"));

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

- `detect(File)` and `detect(InputStream)` give the same result, except for files between 40 and 100 MB that need a pattern in the middle: a file is read completely, a stream only at both ends. A stream is not closed. If it supports `mark()`/`reset()` it is reset to its start before reading; otherwise reading starts at its current position.
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

`getSignatureCountForMimeType()` returns 0 for unknown types and for types without any signature; skipping the shortcut then saves reading the stream twice.

## The signature file

The signatures come from PRONOM. `src/main/resources/DROID_SignatureFile_V125_experimental.xml` is the signature file the library is built and tested with. It is PRONOM's DROID signature file V125 (2,273 signatures) plus eleven signatures that PRONOM does not have, 2,284 in total. The name says "experimental" because the file deviates from the official V125 file, which this project cannot change; PRONOM's own file is kept unchanged next to it as `DROID_SignatureFile_V125.xml`. The additions are:

- **EOT font, version 1.2**
- **SVG without an XML declaration**: the PRONOM signatures for SVG require `<?xml version="1.0"` at the very start of the file, so SVG files that begin directly with `<svg` were not recognised. The added signature maps them to the generic SVG format `fmt/91`.
- **JSON, JavaScript and CSS**: nine heuristic signatures, because PRONOM has no binary signature for these text formats and web archives (WARC files) are full of them. They are described below.

New releases of the signature file are published by PRONOM on the [DROID signature files page](https://pronom.nationalarchives.gov.uk/signature-list). To use another release, load it with the `File` or `InputStream` constructor.

### Heuristic signatures for JSON, JavaScript and CSS

JSON, JavaScript and CSS are text formats without magic bytes, so these signatures only look for syntax that is typical of the format:

- **JSON** (`fmt/817`): a `{` or `[` within the first 3 bytes. An object (`{`) must be followed directly by a quote or a whitespace character (space, tab, line feed or carriage return), and a quote must follow within the next 12 bytes. An array (`[`) must be followed within 12 bytes by `{`, a quote or `[`.
- **JavaScript** (`x-fmt/423`): one of `return`, `module.exports`, `export `, `use strict`, `import {` or `var ` within the first 3,000 bytes.
- **CSS** (`x-fmt/224`): a `{` within the first 3,000 bytes, followed within 200 bytes by a common CSS property word (`color`, `margin`, `padding`, `display`, `font`, `background`, `border`, `width`, `height`, `position` and a few dozen more) or by `--`, and then a `:` shortly after that word.

The three formats are the last ones in the format list of the signature file, in the order JSON, JavaScript, CSS. When several formats match a file, they are reported in the order of the format list, except that a format that another matching format outranks by a PRONOM priority rule comes after the others. So a file that matches one of these three and a format with a real PRONOM signature is reported as the real format first, and a file that matches several of the three is reported as JSON before JavaScript before CSS.

The results are heuristic. Measured on 9,105 real files from 58 npm packages, the signatures identified 99.6% of the JavaScript files (1.3% false positives), 98.4% of the CSS files (0.11% false positives) and 100% of the JSON files (0.06% false positives). On an independent set of assets from Python packages they identified 100% of the JSON files, 92% of the CSS files and 78% of the JavaScript files; the JavaScript files that are missed are mostly data files without any of the keywords. None of 288 binary files that no PRONOM signature identifies was labelled as CSS, JavaScript or JSON, and `NoFalsePositivesOnBinaryDataTest` checks that random bytes and the payload of the binary test samples never get one of the three formats. Known false positives are text files of other kinds that contain the same syntax, for example Django and Jinja template files. One case among the test samples: jQuery 1.7.1 (a minified JavaScript file) contains the text `<!doctype html>` and is therefore reported as HTML5 (`fmt/471`) first and as JavaScript second.

## Limits

- **No guessing.** Only binary signatures are used, never file extensions. Only the formats that have a signature in the signature file are identified; plain text, for example, is not. CSS, JavaScript and JSON are identified by heuristic signatures (see above), which can be wrong.
- **No container signatures.** DROID also looks inside ZIP and OLE2 containers to tell, for example, a Word document from a spreadsheet. droid-light does not; such files are reported by their binary signature only.
- **Damaged or truncated files** are not identified when the signature needs something at the end of the file (the `%%EOF` of a PDF, the end of a PNG, the end marker of a JPEG). An empty result is the correct answer then.
- **Search distance.** A pattern is looked for within the first or last 3,000 bytes of the file. Signatures that allow a larger distance in the signature file are searched that far; the end marker of a JPEG, for example, may be followed by up to 131,072 bytes of other data.

## Building

Java 11 or later: `mvn clean install`. The signature file is bundled in the jar.

## License

BSD 3-Clause, see [LICENSE](LICENSE). DROID is released under the New BSD License as well. droid-light is an independent implementation that reads the same signature file; it is not affiliated with or endorsed by The National Archives.

## Third-party material

**The signature file.** `DROID_SignatureFile_V125_experimental.xml` is based on `DROID_SignatureFile_V125.xml`, which was created by the PRONOM project of The National Archives, which publishes its content under the [Open Government Licence v3.0](https://www.nationalarchives.gov.uk/doc/open-government-licence/version/3/). The file is not covered by this project's BSD license. Besides PRONOM's signatures it contains the signatures added by this project (see above).

> Contains public sector information licensed under the Open Government Licence v3.0.

**DROID.** Parts of the matching behaviour follow [DROID](https://github.com/digital-preservation/droid), the file format identification tool developed by The National Archives and released under the New BSD License.

**Test samples.** The files in `src/test/resources/pronom-samples` are unmodified files from other projects, or files written by ffmpeg from a clip; they are test data only and are not covered by this project's BSD license. `MANIFEST.json` records for every file where it comes from (repository, commit and path) and under which licence it is published. Newer samples come from these sources:

- [Apache POI](https://github.com/apache/poi), [Apache Tika](https://github.com/apache/tika), [Apache Commons Compress](https://github.com/apache/commons-compress) and [Apache Commons Imaging](https://github.com/apache/commons-imaging), and [pdf.js](https://github.com/mozilla/pdf.js): Apache-2.0. For some files the repository does not document where the file itself comes from; the manifest says so.
- [openexr-images](https://github.com/AcademySoftwareFoundation/openexr-images) (OpenEXR images): BSD-3-Clause.
- [AOMediaCodec/av1-avif](https://github.com/AOMediaCodec/av1-avif): BSD-2-Clause.
- [OpenImageIO-images](https://github.com/AcademySoftwareFoundation/OpenImageIO-images): the licence differs per file, and the manifest gives it for each one: the libtiff licence, Apache-2.0 (the OpenImageIO project), or "no expressive content" (as stated in the repository's THIRD-PARTY.md).
- [ianare/exif-samples](https://github.com/ianare/exif-samples): CC BY-SA 4.0 (user-contributed). The three JPEG files taken from it are unmodified and stay under that licence.
- [Nokia heif_conformance](https://github.com/nokiatech/heif_conformance) and [openjpeg-data](https://github.com/uclouvain/openjpeg-data): no licence is stated for the files taken from them.
- [OpenMPT](https://github.com/OpenMPT/openmpt) (tracker modules): BSD-3-Clause.
- [Ruffle](https://github.com/ruffle-rs/ruffle) test SWFs: MIT OR Apache-2.0. The files under `from_shumway` come from the Mozilla Shumway test corpus and are Apache-2.0; the manifest gives the licence for each file.
- Big Buck Bunny clips from [opencv_extra](https://github.com/opencv/opencv_extra): the content is licensed CC BY 3.0, (c) Blender Foundation, [www.bigbuckbunny.org](https://www.bigbuckbunny.org). opencv_extra itself states no licence. Some media samples were made with ffmpeg from one such clip; for those the manifest gives the exact command line instead of a source path.
- CSS, JavaScript and JSON samples (these formats have no binary signature in PRONOM's file): [pallets/flask](https://github.com/pallets/flask) (a tutorial stylesheet, BSD-3-Clause), [kognise/water.css](https://github.com/kognise/water.css) (a minified stylesheet, MIT), [git/git-scm.com](https://github.com/git/git-scm.com) (a script and a minified jQuery 1.7.1; the repository's own code is MIT, and jQuery is MIT), [samayo/country-json](https://github.com/samayo/country-json) (MIT) and [Fyrd/caniuse](https://github.com/Fyrd/caniuse) (data licensed CC BY 4.0).

## Removed: Aho-Corasick detector

An alternative detector based on the Aho-Corasick algorithm, which finds the start patterns of all signatures in a single pass, was removed. It meant maintaining two implementations of the same rules, and it only paid off when every signature had to be searched across very large parts of a file, which normal detection does not need. The last version is kept in the git tag `aho-corasick-archive`.
