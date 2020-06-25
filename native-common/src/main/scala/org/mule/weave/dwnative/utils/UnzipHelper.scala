package org.mule.weave.dwnative.utils

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

object UnzipHelper {

  /**
    * Unzips the
    * @param zipFile The zip stream
    * @param outputFolder The folder where to unzip it
    */
  def unZipIt(zipFile: InputStream, outputFolder: File): Unit = {
    val buffer = new Array[Byte](1024)
    try {
      //output directory
      val folder: File = outputFolder;
      if (!folder.exists()) {
        folder.mkdirs()
      }
      //zip file content
      val zis: ZipInputStream = new ZipInputStream(zipFile)
      //get the zipped file list entry
      var ze: ZipEntry = zis.getNextEntry();

      while (ze != null) {
        val fileName = ze.getName();
        val newFile = new File(outputFolder + File.separator + fileName);
        //create folders
        new File(newFile.getParent()).mkdirs();
        val fos = new FileOutputStream(newFile);
        var len: Int = zis.read(buffer);
        while (len > 0) {
          fos.write(buffer, 0, len)
          len = zis.read(buffer)
        }
        fos.close()
        ze = zis.getNextEntry()
      }
      zis.closeEntry()
      zis.close()
    } catch {
      case e: IOException => println("exception caught: " + e.getMessage)
    }
  }
}
