package services

import scalikejdbc._
import cats.effect.{IO, Resource}
import java.time.{Instant, ZoneId}

final case class DbFileRow(
  id: String,
  filename: String,
  size: Long,
  fileType: String,
  checksum: String,
  content: Array[Byte],
  uploadedAt: Instant,
  status: String,
  error: Option[String]
)

final case class DbExtractionRow(
  fileId: String,
  category: String,
  confidence: Double,
  dataJson: String,
  extractedAt: Instant,
  modelUsed: String
)

class DatabaseService {
  def init(): IO[Unit] = IO {
    Class.forName("org.h2.Driver")
    ConnectionPool.singleton("jdbc:h2:mem:tunic;DB_CLOSE_DELAY=-1", "sa", "")
    implicit val session: DBSession = AutoSession
    sql"""
      CREATE TABLE IF NOT EXISTS files (
        id VARCHAR(36) PRIMARY KEY,
        filename VARCHAR(255) NOT NULL,
        file_size BIGINT NOT NULL,
        file_type VARCHAR(50) NOT NULL,
        checksum VARCHAR(64) NOT NULL,
        file_content BLOB NOT NULL,
        upload_timestamp TIMESTAMP NOT NULL,
        processing_status VARCHAR(20) NOT NULL,
        error_message CLOB
      )
    """.execute.apply()

    sql"""
      CREATE TABLE IF NOT EXISTS extractions (
        id VARCHAR(36) PRIMARY KEY,
        file_id VARCHAR(36) NOT NULL,
        category VARCHAR(50) NOT NULL,
        confidence_score DECIMAL(4,3) NOT NULL,
        extracted_data_json CLOB NOT NULL,
        extraction_timestamp TIMESTAMP NOT NULL,
        model_used VARCHAR(100) NOT NULL,
        FOREIGN KEY (file_id) REFERENCES files(id)
      )
    """.execute.apply()
  }

  def insertFile(
    id: String,
    filename: String,
    size: Long,
    fileType: String,
    checksum: String,
    content: Array[Byte],
    status: String,
    error: Option[String]
  ): IO[Unit] = IO {
    implicit val session: DBSession = AutoSession
    sql"""
      MERGE INTO files (id, filename, file_size, file_type, checksum, file_content, upload_timestamp, processing_status, error_message)
      VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(), ?, ?)
    """.bind(id, filename, size, fileType, checksum, content, status, error.orNull).update.apply()
  }

  def getFile(fileId: String): IO[Option[DbFileRow]] = IO {
    implicit val session: DBSession = ReadOnlyAutoSession
    sql"""
      SELECT id, filename, file_size, file_type, checksum, file_content, upload_timestamp, processing_status, error_message
      FROM files WHERE id = ?
    """.bind(fileId).map { rs =>
      DbFileRow(
        id = rs.string("id"),
        filename = rs.string("filename"),
        size = rs.long("file_size"),
        fileType = rs.string("file_type"),
        checksum = rs.string("checksum"),
        content = rs.bytes("file_content"),
        uploadedAt = rs.localDateTime("upload_timestamp").atZone(ZoneId.systemDefault()).toInstant,
        status = rs.string("processing_status"),
        error = Option(rs.string("error_message"))
      )
    }.single.apply()
  }

  def insertExtraction(
    id: String,
    fileId: String,
    category: String,
    confidence: Double,
    dataJson: String,
    modelUsed: String
  ): IO[Unit] = IO {
    implicit val session: DBSession = AutoSession
    sql"""
      INSERT INTO extractions
      (id, file_id, category, confidence_score, extracted_data_json, extraction_timestamp, model_used)
      VALUES
      (?, ?, ?, ?, ?, CURRENT_TIMESTAMP(), ?)
    """.bind(id, fileId, category, BigDecimal(confidence).setScale(3, BigDecimal.RoundingMode.HALF_UP), dataJson, modelUsed)
      .update.apply()
  }

  def fetchExtraction(fileId: String): IO[Option[DbExtractionRow]] = IO {
    implicit val session: DBSession = ReadOnlyAutoSession
    sql"""
      SELECT file_id, category, confidence_score, extracted_data_json, extraction_timestamp, model_used
      FROM extractions WHERE file_id = ?
      ORDER BY extraction_timestamp DESC
      LIMIT 1
    """.bind(fileId).map { rs =>
      DbExtractionRow(
        fileId = rs.string("file_id"),
        category = rs.string("category"),
        confidence = rs.bigDecimal("confidence_score").doubleValue(),
        dataJson = rs.string("extracted_data_json"),
        extractedAt = rs.localDateTime("extraction_timestamp").atZone(ZoneId.systemDefault()).toInstant,
        modelUsed = rs.string("model_used")
      )
    }.single.apply()
  }
}

object DatabaseService {
  val instance = new DatabaseService()
}


