package services

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.charset.StandardCharsets

class FileServiceSpec extends AnyFlatSpec with Matchers {

  val fileService = new FileService()

  "FileService.storeUploadedFile" should "generate deterministic file IDs" in {
    val filename = "test.txt"
    val bytes = "hello world".getBytes(StandardCharsets.UTF_8)
    val contentType = "text/plain"

    val meta1 = fileService.storeUploadedFile(filename, bytes, contentType)
    val meta2 = fileService.storeUploadedFile(filename, bytes, contentType)

    meta1.id shouldBe meta2.id
    meta1.filename shouldBe filename
    meta1.size shouldBe bytes.length.toLong
    meta1.contentType shouldBe contentType
  }

  it should "generate different IDs for different content" in {
    val filename = "test.txt"
    val bytes1 = "hello world".getBytes(StandardCharsets.UTF_8)
    val bytes2 = "goodbye world".getBytes(StandardCharsets.UTF_8)
    val contentType = "text/plain"

    val meta1 = fileService.storeUploadedFile(filename, bytes1, contentType)
    val meta2 = fileService.storeUploadedFile(filename, bytes2, contentType)

    meta1.id should not equal meta2.id
    meta1.checksum should not equal meta2.checksum
  }

  it should "handle binary content types" in {
    val filename = "test.bin"
    val bytes = Array[Byte](1, 2, 3, 4, 5)
    val contentType = "application/octet-stream"

    val meta = fileService.storeUploadedFile(filename, bytes, contentType)
    meta.contentType shouldBe contentType
    meta.size shouldBe bytes.length.toLong
  }

  "FileService.fetchFile" should "retrieve stored files" in {
    val filename = "test.txt"
    val bytes = "hello world".getBytes(StandardCharsets.UTF_8)
    val contentType = "text/plain"

    val meta = fileService.storeUploadedFile(filename, bytes, contentType)
    val fetchResult = fileService.fetchFile(meta.id)

    fetchResult shouldBe defined
    fetchResult.get.filename shouldBe filename
    fetchResult.get.contentType shouldBe contentType
    fetchResult.get.content shouldBe bytes
  }

  it should "return None for non-existent files" in {
    val result = fileService.fetchFile("non-existent-id")
    result shouldBe None
  }
}