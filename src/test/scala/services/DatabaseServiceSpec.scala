package services

import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import cats.effect.testing.scalatest.AsyncIOSpec
import java.nio.charset.StandardCharsets

class DatabaseServiceSpec extends AsyncFlatSpec with Matchers with AsyncIOSpec {

  val dbService = new DatabaseService()

  "DatabaseService" should "initialize tables successfully" in {
    dbService.init().asserting(_ => succeed)
  }

  it should "insert and retrieve files" in {
    val testFile = (
      "test-id",
      "test.txt",
      100L,
      "text/plain",
      "abc123",
      "hello world".getBytes(StandardCharsets.UTF_8),
      "completed",
      None
    )

    for {
      _ <- dbService.init()
      _ <- dbService.insertFile(
        testFile._1, testFile._2, testFile._3, testFile._4,
        testFile._5, testFile._6, testFile._7, testFile._8
      )
      retrieved <- dbService.getFile(testFile._1)
    } yield {
      retrieved shouldBe defined
      val file = retrieved.get
      file.id shouldBe testFile._1
      file.filename shouldBe testFile._2
      file.size shouldBe testFile._3
      file.fileType shouldBe testFile._4
      file.checksum shouldBe testFile._5
      file.status shouldBe testFile._7
    }
  }

  it should "handle MERGE operations for duplicate files" in {
    val fileId = "duplicate-test-id"
    val filename = "test.txt"
    val bytes = "content".getBytes(StandardCharsets.UTF_8)

    for {
      _ <- dbService.init()
      // Insert first time
      _ <- dbService.insertFile(fileId, filename, 100L, "text/plain", "hash1", bytes, "processing", None)
      // Insert again with different status (should update)
      _ <- dbService.insertFile(fileId, filename, 100L, "text/plain", "hash1", bytes, "completed", None)
      retrieved <- dbService.getFile(fileId)
    } yield {
      retrieved shouldBe defined
      retrieved.get.status shouldBe "completed"
    }
  }

  it should "return None for non-existent files" in {
    for {
      _ <- dbService.init()
      result <- dbService.getFile("non-existent-id")
    } yield {
      result shouldBe None
    }
  }

  it should "insert and retrieve extractions" in {
    val extractionData = """{"entities": [], "dates": [], "tables": []}"""
    
    for {
      _ <- dbService.init()
      // First insert a file
      _ <- dbService.insertFile("file-id", "test.txt", 100L, "text/plain", "hash", 
                               "content".getBytes(), "completed", None)
      // Then insert extraction
      _ <- dbService.insertExtraction("extraction-id", "file-id", "invoice", 0.95, extractionData, "gpt-4")
      retrieved <- dbService.fetchExtraction("file-id")
    } yield {
      retrieved shouldBe defined
      val extraction = retrieved.get
      extraction.fileId shouldBe "file-id"
      extraction.category shouldBe "invoice"
      extraction.confidence shouldBe 0.95
      extraction.modelUsed shouldBe "gpt-4"
      extraction.dataJson shouldBe extractionData
    }
  }
}