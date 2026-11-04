# Designing and changing signatures

droid-light uses the official PRONOM signature file unchanged and adds a small number of
**local signatures** on top of it (EOT 1.2, SVG without an XML declaration, and heuristics for
JSON, CSS and JavaScript, whose formats have no byte signature in PRONOM). This page explains
the rules for adding or changing local signatures, and how to test a change. Read it before
touching a signature.

## Principles

1. **The official file is never edited.** `DROID_SignatureFile_V125.xml` is kept as downloaded,
   unmodified, next to the experimental file in `src/main/resources`. It is PRONOM V125,
   released 2 September 2026 and published under the Open Government Licence v3.0
   (https://pronom.nationalarchives.gov.uk/signatures/DROID_SignatureFile_V125.xml). Its SHA-256
   is `c7c328dd0e131947667e9b94294c5d47e0b6ec8ec3040b7fd542ac605ecc6ec5` (3,713,176 bytes).
   The file the library loads, `DROID_SignatureFile_V125_experimental.xml`, is a copy plus local
   additions. The local additions are exactly the difference between the two files and can be
   listed at any time with:

   ```bash
   diff -u src/main/resources/DROID_SignatureFile_V125.xml src/main/resources/DROID_SignatureFile_V125_experimental.xml
   ```
2. **Only add, never alter.** No existing PRONOM signature, extension, MIME type or priority is
   changed. A local addition is a new `InternalSignature` plus an `InternalSignatureID`
   reference on a format. Local IDs are 9761 and above, far from PRONOM's numbering.
3. **A missed file is better than a wrong label.** A heuristic that fires on the wrong kind of
   file is worse than no result. Prefer strict rules and accept lower recall.
4. **Signatures must be cheap.** Every signature is tried on every file. Use long anchors, small
   search windows, and alternatives inside one signature, not many signatures.
5. **Heuristic formats rank last.** Results are ordered by the position of the format in the
   signature file, and priority rules only remove lower-priority formats. The three heuristic
   formats (JSON, JavaScript, CSS) are therefore the last formats in the file, in that order,
   so that any real signature outranks them. Do not add long `HasPriorityOverFileFormatID`
   lists to existing formats.

## What DROID does (for reference)

* DROID 6 ranks matches only with `HasPriorityOverFileFormatID`. Priority decides between
  formats that **both** match a file. It cannot help when nothing else matches, which is the
  case for almost all false positives of a heuristic.
* The `Specificity` attribute (Specific/Generic) on `InternalSignature` is parsed but marked
  deprecated in DROID's source ("not used in DROID 5 and above"). All PRONOM signatures are
  "Specific". droid-light ignores it. There is no "lowest priority" setting in the format.
* A `RightFragment`/`LeftFragment` may have several alternatives at the same position. This is
  the way to express "one of these words", at the cost of a few comparisons.

## Why samples are not enough

A heuristic that is only tested on files that should match always looks good. The mistakes
found so far were all invisible to tests that used positive samples only:

* A CSS rule "`{`, then `:`, then `;` within 200 bytes" matched 78-98% of **random binary data**.
* The word `return` as a JavaScript anchor labelled about half of all plain-text, Python,
  PHP, C and Go files as JavaScript, because it is an ordinary word and keyword.
* Unquoted `use strict` is also Perl; `export ` and `import {` occur in prose and
  documentation; `{` followed by a quote also starts template files (`{% extends "x" %}`).
* jQuery 1.7.1 contains the string `<!doctype html>` at byte 478, so PRONOM's own HTML5
  signature matches it.

Every change to a heuristic must therefore be tested against **negative** files.

## How to test a change

Corpora are built locally and are **never committed** (size and licences; do not copy GPL test
data into this repository). Keep them outside the project, for example in `~/sigcorpus`.

### 1. Positive files (the format the signature is for)

Use real files from many independent sources. Keep one folder per source (package), so the set
can be split by source.

```bash
mkdir -p ~/sigcorpus/positive ~/sigcorpus/negative && cd ~/sigcorpus/positive

# JavaScript, CSS and JSON from npm packages
for p in jquery lodash underscore backbone react vue d3 moment axios bootstrap normalize.css \
         font-awesome animate.css bulma leaflet highlight.js marked mime-db; do
  npm pack "$p" --silent
done
for f in *.tgz; do mkdir -p "${f%.tgz}" && tar -xzf "$f" -C "${f%.tgz}"; done

# an independent second set from Python packages (assets in wheels)
mkdir -p ~/sigcorpus/wheels && pip download Django sphinx-rtd-theme mkdocs-material --no-deps -d ~/sigcorpus/wheels
for w in ~/sigcorpus/wheels/*.whl; do unzip -q -o "$w" -d ~/sigcorpus/pypi/"$(basename "$w" .whl)"; done
```

Label files by extension (`.js .mjs .cjs`, `.css`, `.json .map`) and ignore ambiguous ones
(`.ts .jsx .vue .scss`). Split by **source**: design with half of the packages, measure on the
other half, and keep a second, never-used set (the Python packages above) for the final check.
For example, to assign each package folder to the design or the test half:

```bash
for d in */; do if [ $(( $(printf %s "$d" | cksum | cut -d' ' -f1) % 2 )) -eq 0 ]; then echo "design $d"; else echo "test $d"; fi; done
```

### 2. Negative files (what must NOT match)

```bash
cd ~/sigcorpus/negative
# source code in other languages
git clone --depth 1 https://github.com/DaveGamble/cJSON c-cjson
git clone --depth 1 https://github.com/google/gson java-gson
git clone --depth 1 https://github.com/guzzle/guzzle php-guzzle
git clone --depth 1 https://github.com/sinatra/sinatra ruby-sinatra
git clone --depth 1 https://github.com/spf13/cobra go-cobra
# Python source (from the unzipped wheels) and plain-English text
mkdir -p prose && cp /usr/share/common-licenses/* prose/ 2>/dev/null
# unidentified binary data: random bytes of several sizes
mkdir -p random && for s in 1500 4000 64000 1000000; do for i in $(seq 1 20); do head -c $s /dev/urandom > random/rand_${s}_$i.bin; done; done
# damaged real files: the first 64 bytes removed, so no signature can match
mkdir -p damaged && for f in ../../project/src/test/resources/pronom-samples/*; do
  [ "$(stat -c %s "$f")" -gt 4000 ] && tail -c +65 "$f" > "damaged/$(basename "$f")"; done
```

(Adjust the path to the samples folder.) Also include real CSS, JSON and other text formats
when checking a rule for one of them.

### 3. What to measure and report

Write the evaluation as a small Java program or JUnit test under `src/test` (corpus folders
given as system properties; skipped when absent). Do not rely on external scripts. For each
signature file under test (the official file, the current file, the candidate), report:

* **Recall**: share of the positive files whose **first** result is the right format, per
  corpus and per package (the worst package matters). Also report "any result".
* **False positives**: number and share of negative files per group (Python, prose, PHP, C,
  Java, Go, Ruby, CSS, random binary, damaged binary) whose first result is the heuristic
  format. Unidentified binary data must give **zero**.
* **Per anchor**: for every anchor, its false positives per group and the positive files that
  only this anchor catches. Drop anchors that cost more false positives than they earn.
* **Speed**: parse time and average detection time per file for binary and for text files,
  compared with the official file. Run candidates **one after another**, never in parallel,
  in at least two rounds, and treat differences within the noise as no difference. Also report
  the growth of the signature file (bytes, signatures, fragments).
* **Regression**: the result list of every existing sample must stay identical, except for the
  files the new signature is meant to catch.

### 4. Acceptance

* Zero false positives on unidentified binary data (random and damaged files).
* Very low false positives (well below 1%) on every other negative group; every remaining case
  explained in the commit message.
* No measurable slowdown per file; parse time unchanged within noise.
* A small, reviewable diff to the official file. Prefer one signature with alternatives over
  several signatures.
* All tests pass, and no sample, label or expectation was changed to make a test pass.

## Comparing the official and the experimental file

Both files are in the project, so a change can be checked for false detections by running both
on the same corpus (positive and negative files, as described above) and comparing the results.
Only these formats may differ, because only they have local signatures: fmt/91, fmt/DRAFT-EOT-1.2,
fmt/817, x-fmt/224 and x-fmt/423. For every other format the results must be identical, and where
the official file finds nothing the experimental file may only add one of those five formats.
Anything else is a mistake in a local signature.

## Design rules for heuristics

* Anchors of at least six bytes where possible; Boyer-Moore search skips quickly through them.
  A one- or two-byte anchor is only acceptable at a fixed offset.
* The search window as small as the evidence allows, and near the start of the file for
  languages that announce themselves there. Do not widen a window to gain a fraction of a
  percent: deeper windows add false positives faster than they add recall.
* Generic words (`return`, `function`, `var`, `if`, `this`) are not evidence on their own. Use
  language-specific tokens, or require a code-like continuation after a short anchor
  (`export ` followed by `default`, `const `, `function`, `{` ...; `import {` with `} from`
  after it).
* Every cue that is only a word needs a negative test with that word in other languages and in
  English text.
* Do not tune a rule against one sample or one package. If the only way to make a file match
  is a rule that matches other things, accept the miss.

## Reference figures (PRONOM V125, strict rules)

Measured on 9,105 real files from 58 npm packages, 3,402 non-JavaScript text files and 288
unidentified binary files (random and damaged real files):

| Format | Recall | Notes |
|---|---|---|
| JavaScript | 95.6% | 3 of 3,402 non-JavaScript text files labelled JavaScript |
| CSS | 98.4% | false positives about 0.1% |
| JSON | 100% | false positives about 0.06% |
| unidentified binary data | n/a | 0 of 288 labelled CSS, JavaScript or JSON |

On an independent set (Python packages) JavaScript recall is lower (about 66%) because most of
the misses are one package's generated files without any JavaScript keyword. Known false
positives are template source files (`{% ... %}`), which a web server does not deliver. Use
these figures as the baseline to beat or match, never to excuse a regression.

## Updating to a new PRONOM release

1. Download the new release from https://pronom.nationalarchives.gov.uk/signature-list and
   verify it. Keep it unmodified in `src/main/resources`.
2. Save the local additions as a temporary diff, copy the new official file to the new
   experimental file name, and apply the diff to the copy with `patch`:

   ```bash
   cd src/main/resources
   diff -u DROID_SignatureFile_V125.xml DROID_SignatureFile_V125_experimental.xml > /tmp/local.diff
   cp DROID_SignatureFile_V<new>.xml DROID_SignatureFile_V<new>_experimental.xml
   patch DROID_SignatureFile_V<new>_experimental.xml < /tmp/local.diff
   ```

   If the diff does not apply cleanly, re-add the local signatures and their references by hand,
   following the diff. Never edit the official signatures.
3. Run the full test suite and the corpus comparison above for the old and the new file.
   Nothing in the results should change unless the new release itself changed a format.
4. Delete the old official and experimental files, so that only the current pair remains in
   `src/main/resources`, and update the version, the release date and the SHA-256 in this guide
   and in the README.
