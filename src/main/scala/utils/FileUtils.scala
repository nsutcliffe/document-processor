package utils

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.tika.Tika
import java.io.ByteArrayInputStream
import org.slf4j.LoggerFactory

object FileUtils {
  
  private val logger = LoggerFactory.getLogger(getClass)
  private val tika = new Tika()
  
  def detectFileType(bytes: Array[Byte], filename: String): String = {
    val detected = tika.detect(bytes, filename)
    logger.debug(s"FileUtils.detectFileType: Tika detected MIME type: $detected for file: $filename")
    detected match {
      case mime if mime.startsWith("image/") => 
        if (mime.contains("png")) "png"
        else if (mime.contains("jpeg") || mime.contains("jpg")) "jpeg"
        else {
          // Handle other image types or fallback based on filename
          val lowerFilename = filename.toLowerCase
          if (lowerFilename.endsWith(".jpg") || lowerFilename.endsWith(".jpeg")) "jpeg"
          else if (lowerFilename.endsWith(".png")) "png"
          else "image"
        }
      case mime if mime.contains("pdf") => "pdf"
      case _ => 
        // Fallback to extension if Tika is too generic or incorrect
        val lowerFilename = filename.toLowerCase
        if (lowerFilename.endsWith(".pdf")) "pdf"
        else if (lowerFilename.endsWith(".png")) "png"
        else if (lowerFilename.endsWith(".jpg") || lowerFilename.endsWith(".jpeg")) "jpeg"
        else "other"
    }
  }
  
  def hasImages(bytes: Array[Byte], fileType: String): Boolean = {
    logger.debug(s"FileUtils.hasImages: Checking fileType: $fileType")
    fileType match {
      case "png" | "jpeg" | "jpg" | "image" => true  // Added "image" as fallback
      case "pdf" => checkPdfForImages(bytes)
      case _ => false
    }
  }
  
  private def checkPdfForImages(bytes: Array[Byte]): Boolean = {
    try {
      val document = PDDocument.load(new ByteArrayInputStream(bytes))
      try {
        val pages = document.getPages
        var hasImages = false
        val iterator = pages.iterator()
        
        while (iterator.hasNext && !hasImages) {
          val page = iterator.next()
          val resources = page.getResources
          if (resources != null && resources.getXObjectNames.iterator().hasNext) {
            hasImages = true
          }
        }
        hasImages
      } finally {
        document.close()
      }
    } catch {
      case _: Exception => false // If we can't parse PDF, assume no images
    }
  }
  
  def extractTextContent(bytes: Array[Byte], fileType: String): String = {
    fileType match {
      case "pdf" => extractPdfText(bytes)
      case _ => s"[Binary file of type: $fileType, size: ${bytes.length} bytes]"
    }
  }
  
  private def extractPdfText(bytes: Array[Byte]): String = {
    try {
      val document = PDDocument.load(new ByteArrayInputStream(bytes))
      try {
        val stripper = new org.apache.pdfbox.text.PDFTextStripper()
        stripper.getText(document)
      } finally {
        document.close()
      }
    } catch {
      case _: Exception => s"[PDF text extraction failed, size: ${bytes.length} bytes]"
    }
  }
}