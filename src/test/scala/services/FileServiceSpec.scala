package services

import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import cats.effect.testing.scalatest.AsyncIOSpec
import org.http4s.Headers
import org.http4s.headers.`Content-Type`
import org.http4s.MediaType
import java.nio.charset.StandardCharsets

class FileServiceSpec extends AsyncFlatSpec with Matchers with AsyncIOSpec {

  val fileService = new FileService()

  "FileService.storeUploadedFile" should "generate deterministic file IDs" in {
    val filename = "test.txt"
    val bytes = "hello world".getBytes(StandardCharsets.UTF_8)
    val headers = Headers(`Content-Type`(MediaType.text.plain))

    for {
      meta1 <- fileService.storeUploadedFile(filename, bytes, headers)
      meta2 <- fileService.storeUploadedFile(filename, bytes, headers)
    } yield {
      meta1.id shouldBe meta2.id
      meta1.filename shouldBe filename
      meta1.size shouldBe bytes.length.toLong
      meta1.contentType should include("text/plain")
    }
  }

  it should "generate different IDs for different content" in {
    val filename = "test.txt"
    val bytes1 = "hello world".getBytes(StandardCharsets.UTF_8)
    val bytes2 = "goodbye world".getBytes(StandardCharsets.UTF_8)
    val headers = Headers(`Content-Type`(MediaType.text.plain))

    for {
      meta1 <- fileService.storeUploadedFile(filename, bytes1, headers)
      meta2 <- fileService.storeUploadedFile(filename, bytes2, headers)
    } yield {
      meta1.id should not equal meta2.id
      meta1.checksum should not equal meta2.checksum
    }
  }

  it should "handle missing content type headers" in {
    val filename = "test.bin"
    val bytes = Array[Byte](1, 2, 3, 4, 5)
    val headers = Headers.empty

    fileService.storeUploadedFile(filename, bytes, headers).asserting { meta =>
      meta.contentType shouldBe "application/octet-stream"
    }
  }

  "FileService.fetchFile" should "retrieve stored files" in {
    val filename = "test.txt"
    val bytes = "hello world".getBytes(StandardCharsets.UTF_8)
    val headers = Headers(`Content-Type`(MediaType.text.plain))

    for {
      meta <- fileService.storeUploadedFile(filename, bytes, headers)
      fetchResult <- fileService.fetchFile(meta.id)
    } yield {
      fetchResult shouldBe defined
      fetchResult.get.filename should include(meta.id)
    }
  }

  it should "return None for non-existent files" in {
    fileService.fetchFile("non-existent-id").asserting(_ shouldBe None)
  }
}