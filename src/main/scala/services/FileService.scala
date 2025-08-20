package services

import java.util.zip.CRC32
import org.slf4j.LoggerFactory

final case class StoredFileMeta(id: String, filename: String, size: Long, contentType: String, checksum: String)
final case class FileBytes(filename: String, contentType: String, content: Array[Byte])

class FileService {
  
  private val logger = LoggerFactory.getLogger(getClass)
  
  // In-memory storage for file content (for simplicity in this prototype)
  private val contentStore = scala.collection.concurrent.TrieMap.empty[String, Array[Byte]]
  private val metaStore = scala.collection.concurrent.TrieMap.empty[String, StoredFileMeta]

  def storeUploadedFile(filename: String, bytes: Array[Byte], contentType: String): StoredFileMeta = {
    val checksum = computeCrc32(bytes)
    val id = s"${filename.hashCode.abs}-$checksum" // Deterministic ID based on filename + content
    
    logger.debug(s"Storing file: $filename -> $id (${bytes.length} bytes, $contentType)")
    
    contentStore.put(id, bytes)
    val meta = StoredFileMeta(id, filename, bytes.length.toLong, contentType, checksum)
    metaStore.put(id, meta)
    
    meta
  }

  def fetchFile(fileId: String): Option[FileBytes] = {
    logger.debug(s"Fetching file: $fileId")
    
    for {
      content <- contentStore.get(fileId)
      meta <- metaStore.get(fileId)
    } yield FileBytes(meta.filename, meta.contentType, content)
  }

  private def computeCrc32(bytes: Array[Byte]): String = {
    val crc = new CRC32()
    crc.update(bytes)
    java.lang.Long.toHexString(crc.getValue)
  }
}