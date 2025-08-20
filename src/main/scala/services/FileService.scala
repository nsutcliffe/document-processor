package services

import cats.effect.IO
import org.http4s.Headers
import java.util.zip.CRC32
import java.util.UUID
import fs2.Stream

final case class StoredFileMeta(id: String, filename: String, size: Long, contentType: String, checksum: String)
final case class FileBytes(filename: String, contentType: String, content: Stream[IO, Byte])

class FileService {
  // TODO: replace with DB. For bootstrap, keep in-memory map for content; DB for metadata in later step.
  private val contentStore = scala.collection.concurrent.TrieMap.empty[String, Array[Byte]]

  def storeUploadedFile(filename: String, bytes: Array[Byte], headers: Headers): IO[StoredFileMeta] = IO {
    val checksum = computeCrc32(bytes)
    val id = s"${filename.hashCode.abs}-$checksum" // Use deterministic ID based on filename + content
    val ct = headers.get[org.http4s.headers.`Content-Type`]
      .map(h => h.mediaType.toString)
      .getOrElse("application/octet-stream")
    contentStore.put(id, bytes)
    StoredFileMeta(id, filename, bytes.length.toLong, ct, checksum)
  }

  def fetchFile(fileId: String): IO[Option[FileBytes]] = IO {
    contentStore.get(fileId).map { b =>
      FileBytes(filename = s"file-$fileId", contentType = "application/octet-stream", content = Stream.emits(b).covary[IO])
    }
  }

  private def computeCrc32(bytes: Array[Byte]): String = {
    val crc = new CRC32()
    crc.update(bytes)
    java.lang.Long.toHexString(crc.getValue)
  }
}


