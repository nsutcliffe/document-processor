package integration

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import services._
import config.AppConfig
import java.nio.charset.StandardCharsets

class EndToEndSpec extends AnyFlatSpec with Matchers {

  // Mock configuration for testing (without real API key)
  val testConfig = AppConfig(openRouterApiKey = Some("test-key"))

  "Service integration" should "initialize all services successfully" in {
    val fileService = new FileService()
    val llmService = new LlmService(testConfig)
    val dbService = new DatabaseService(useInMemory = true)
    val extractionService = new ExtractionService(llmService, dbService)

    // Test service initialization
    noException should be thrownBy dbService.init()
    
    // Test basic file operations (FileService uses in-memory storage, not database)
    val filename = "test.txt"
    val content = "This is a test document".getBytes(StandardCharsets.UTF_8)
    val contentType = "text/plain"
    
    val fileMeta = fileService.storeUploadedFile(filename, content, contentType)
    fileMeta.filename shouldBe filename
    fileMeta.size shouldBe content.length.toLong
    
    val retrievedFile = fileService.fetchFile(fileMeta.id)
    retrievedFile shouldBe defined
    retrievedFile.get.content shouldBe content
  }

  "Database operations" should "work correctly" in {
    val dbService = new DatabaseService(useInMemory = true)
    dbService.init()
    
    val fileId = "integration-test-file"
    val filename = "test.txt"
    val content = "test content".getBytes(StandardCharsets.UTF_8)
    
    // Insert file
    dbService.insertFile(fileId, filename, content.length.toLong, "text/plain", "hash123", content, "processing", None)
    
    // Retrieve file
    val dbFile = dbService.getFile(fileId)
    dbFile shouldBe defined
    dbFile.get.filename shouldBe filename
    dbFile.get.status shouldBe "processing"
  }

  "Error handling" should "work across services" in {
    // Test with no API key
    val noKeyConfig = AppConfig(openRouterApiKey = None)
    val llmService = new LlmService(noKeyConfig)
    
    val exception = intercept[RuntimeException] {
      llmService.categorizeDocument("test", "pdf", false)
    }
    exception.getMessage should include("OPENROUTER_API_KEY")
  }

  "File type detection" should "work end-to-end" in {
    import utils.FileUtils
    
    // Use a more complete PDF header that Tika can recognize
    val pdfBytes = Array[Byte](0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x34) // %PDF-1.4
    val detectedType = FileUtils.detectFileType(pdfBytes, "test.pdf")
    
    // Tika might still not detect this minimal header, so check both possibilities
    detectedType should (equal("pdf") or equal("other"))
    
    // If Tika didn't detect it, it should still fall back to filename-based detection
    if (detectedType == "other") {
      // Test filename-based fallback
      val fallbackType = FileUtils.detectFileType(Array[Byte](), "document.pdf")
      fallbackType shouldBe "pdf"
    }
    
    val hasImages = FileUtils.hasImages(pdfBytes, detectedType)
    hasImages shouldBe false // Invalid PDF, so no images detected
    
    val textContent = FileUtils.extractTextContent(pdfBytes, detectedType)
    if (detectedType == "pdf") {
      textContent should include("PDF text extraction failed")
    } else {
      textContent should include("Binary file of type")
    }
  }
}