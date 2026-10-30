package dk.kb.droidlight;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * FolderScanner - runs DroidSignatureVerifier.detect() against every file in a
 * folder, printing a filename + top-detection-result line for each, so results
 * can be eyeballed at scale against the files' actual extensions.
 *
 * Hardcoded variables, no CLI args - meant to be run directly from an IDE, same
 * style as DroidSignatureVerifier's own main().
 *
 * One DroidSignatureVerifier instance is constructed ONCE (parsing the signature
 * file a single time) and reused for every file in the folder - see
 * DroidSignatureVerifier's constructor/detect() split, which is exactly what
 * makes this efficient for batch use.
 */
public class ScanFolderCompareTest {

    public static void main(String[] args) throws Exception {
        String signatureFile = "/home/teg/eclipse-workspace/droid-light/src/main/resources/DROID_SignatureFile_V124.xml";
        String scan_folder = "/home/teg/eclipse-workspace/droid-light/src/test/resources/pronom-samples";

        File sigFile = new File(signatureFile);
        File folder = new File(scan_folder);

        if (!folder.isDirectory()) {
            System.out.println("Not a directory: " + folder);
            return;
        }

        // List files directly in the folder (not recursive - subdirectories are
        // skipped, not descended into).
        File[] entries = folder.listFiles();
        List<File> files = new ArrayList<>();
        if (entries != null) {
            for (File f : entries) {
                if (f.isFile()) {
                    files.add(f);
                }
            }
        }
        
        long start=System.currentTimeMillis();
        System.out.println("Found " + files.size() + " file(s) in " + folder);

      DroidSignatureVerifier verifier1 = new DroidSignatureVerifier(sigFile); // parses ONCE
      DroidSignatureAhoCorasickVerifier verifier2= new DroidSignatureAhoCorasickVerifier(sigFile);
        
        System.out.println("\n===== Scanning " + files.size() + " file(s) =====\n");

        int errorCount = 0;
        for (File file : files) {
            try {
                System.out.println("Scanning:"+file);
                DetectionResult[] results1 = verifier1.detect(file);
                DetectionResult[] results2 = verifier2.detect(file);
                String t1=results1[0].toString();
                String t2=results2[0].toString();
                if (!t1.equals(t2)) {
                    System.out.println(file);
                    System.out.println(t1);
                    System.out.println(t2);
                    System.exit(1);
                }
                else {
                    System.out.println("match");
                }
               
                
            } catch (Exception e) {
                // Don't let one unreadable/corrupt file abort the whole batch.
                errorCount++;
                System.out.println("RESULT: " + file.getName() + "  ->  ERROR: " + e);
            }
            System.out.println(); // blank line between files, since detect() itself prints
                                   // several diagnostic lines per file (kept, per earlier request)
        }

        System.out.println("===== Done. " + files.size() + " file(s) scanned, " + errorCount + " error(s). =====");
        System.out.println("Total scan time:"+(System.currentTimeMillis()-start));
    }
}