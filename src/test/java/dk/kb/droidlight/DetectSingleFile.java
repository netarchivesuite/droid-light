package dk.kb.droidlight;

import java.io.File;

public class DetectSingleFile {
public static void main(String[] args) throws Exception{
    
    String signatureFile = "/home/teg/eclipse-workspace/droid-light/src/main/resources/DROID_SignatureFile_V125_experimental.xml";
    String file="/home/teg/Downloads/1000000659.jpg";

    File sigFile = new File(signatureFile);
    File scanFile= new File(file);
    DroidSignatureVerifier verifier = new DroidSignatureVerifier(sigFile); // parses ONCE
    
    DetectionResult[] detect = verifier.detect(scanFile);
    System.out.println(detect[0]);
    
}
}
