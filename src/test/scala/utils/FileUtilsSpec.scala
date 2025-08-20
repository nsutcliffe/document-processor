package utils

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.charset.StandardCharsets

class FileUtilsSpec extends AnyFlatSpec with Matchers {

  "FileUtils.detectFileType" should "correctly identify PDF files" in {
    val pdfHeader = Array[Byte](0x25, 0x50, 0x44, 0x46, 0x2D) // %PDF-
    val result = FileUtils.detectFileType(pdfHeader, "test.pdf")
    result shouldBe "pdf"
  }

  it should "correctly identify PNG files" in {
    val pngHeader = Array[Byte](0x89.toByte, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    val result = FileUtils.detectFileType(pngHeader, "test.png")
    result shouldBe "png"
  }

  it should "correctly identify JPEG files" in {
    val jpegHeader = Array[Byte](0xFF.toByte, 0xD8.toByte, 0xFF.toByte)
    val result = FileUtils.detectFileType(jpegHeader, "test.jpg")
    result shouldBe "jpeg"
  }

  it should "fallback to 'other' for unknown file types" in {
    val unknownBytes = "some random text".getBytes(StandardCharsets.UTF_8)
    val result = FileUtils.detectFileType(unknownBytes, "test.txt")
    result shouldBe "other"
  }

  "FileUtils.hasImages" should "return true for image files" in {
    val imageBytes = Array[Byte](0x89.toByte, 0x50, 0x4E, 0x47)
    val result = FileUtils.hasImages(imageBytes, "png")
    result shouldBe true
  }

  it should "return false for non-image files" in {
    val textBytes = "hello world".getBytes(StandardCharsets.UTF_8)
    val result = FileUtils.hasImages(textBytes, "other")
    result shouldBe false
  }

  "FileUtils.extractTextContent" should "handle PDF text extraction gracefully" in {
    val invalidPdfBytes = "not a real pdf".getBytes(StandardCharsets.UTF_8)
    val result = FileUtils.extractTextContent(invalidPdfBytes, "pdf")
    result should include("PDF text extraction failed")
  }

  it should "return binary file description for non-PDF files" in {
    val imageBytes = Array[Byte](0x89.toByte, 0x50, 0x4E, 0x47)
    val result = FileUtils.extractTextContent(imageBytes, "png")
    result should include("Binary file of type: png")
    result should include(s"size: ${imageBytes.length} bytes")
  }
}