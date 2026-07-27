package dk.kb.droidlight;

import java.io.File;

public class FileExtenstionFormatDetectorTest {

    public static void main(String[] args) throws Exception {
        String signatureFile = "/home/teg/eclipse-workspace/droid-light/src/main/resources/DROID_SignatureFile_V124.xml";
        File sigFile=new File(signatureFile);
        
        TentativeFormatDetector fileMimeTypeDetector= new TentativeFormatDetector(sigFile);
        String testFilePath="/home/teg/eclipse-workspace/droid/droid-container/src/test/resources/Book1.xlsx";
        
         FormatInfo info =  fileMimeTypeDetector.detectFromFileName(testFilePath);
         System.out.println("ext:"+info);
          info =  fileMimeTypeDetector.detectFromMimeType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
         System.out.println("ext:"+info);
        
    }
    
}
