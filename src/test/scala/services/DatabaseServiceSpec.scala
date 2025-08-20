package services

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach
import java.nio.charset.StandardCharsets

class DatabaseServiceSpec extends AnyFlatSpec with Matchers {

  // Use a single database instance for all tests in this class
  val dbService = new DatabaseService(useInMemory = true)
  
  // Initialize once for the entire test class
  dbService.init()

  "DatabaseService" should "initialize tables successfully" in {
    // Just verify that init() doesn't throw an exception
    noException should be thrownBy dbService.init()
  }

  it should "insert and retrieve files" in {
    val testFile = (
      "test-id-1",
      "test.txt",
      100L,
      "text/plain",
      "abc123",
      "hello world".getBytes(StandardCharsets.UTF_8),
      "completed",
      None
    )

    dbService.insertFile(
      testFile._1, testFile._2, testFile._3, testFile._4,
      testFile._5, testFile._6, testFile._7, testFile._8
    )
    
    val retrieved = dbService.getFile(testFile._1)
    
    retrieved shouldBe defined
    val file = retrieved.get
    file.id shouldBe testFile._1
    file.filename shouldBe testFile._2
    file.size shouldBe testFile._3
    file.fileType shouldBe testFile._4
    file.checksum shouldBe testFile._5
    file.status shouldBe testFile._7
  }

  it should "handle MERGE operations for duplicate files" in {
    val fileId = "duplicate-test-id-2"
    val filename = "test.txt"
    val bytes = "content".getBytes(StandardCharsets.UTF_8)

    // Insert first time
    dbService.insertFile(fileId, filename, 100L, "text/plain", "hash1", bytes, "processing", None)
    
    // Insert again with different status (should update)
    dbService.insertFile(fileId, filename, 100L, "text/plain", "hash1", bytes, "completed", None)
    
    val retrieved = dbService.getFile(fileId)
    
    retrieved shouldBe defined
    retrieved.get.status shouldBe "completed"
  }

  it should "return None for non-existent files" in {
    val result = dbService.getFile("non-existent-id")
    result shouldBe None
  }

  it should "insert and retrieve extractions" in {
    val extractionData = """{"entities": [], "dates": [], "tables": []}"""
    
    // First insert a file
    dbService.insertFile("file-id-3", "test.txt", 100L, "text/plain", "hash", 
                         "content".getBytes(), "completed", None)
    
    // Then insert extraction
    dbService.insertExtraction("extraction-id-3", "file-id-3", "invoice", 0.95, extractionData, "gpt-4")
    
    val retrieved = dbService.fetchExtraction("file-id-3")
    
    retrieved shouldBe defined
    val extraction = retrieved.get
    extraction.fileId shouldBe "file-id-3"
    extraction.category shouldBe "invoice"
    extraction.confidence shouldBe 0.95
    extraction.modelUsed shouldBe "gpt-4"
    extraction.dataJson shouldBe extractionData
  }
}