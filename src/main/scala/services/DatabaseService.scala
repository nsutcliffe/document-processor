package services

import scalikejdbc._
import java.time.{Instant, ZoneId}
import org.slf4j.LoggerFactory

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

final case class FileSummary(
  id: String,
  filename: String,
  uploadedAt: Instant,
  status: String,
  category: Option[String]
)

class DatabaseService(useInMemory: Boolean = false) {
  
  private val logger = LoggerFactory.getLogger(getClass)

  def init(): Unit = {
    logger.info("Initializing database...")
    Class.forName("org.h2.Driver")
    
    if (useInMemory) {
      // Use in-memory database for tests with unique name to avoid conflicts
      val dbName = s"test_${System.currentTimeMillis()}_${scala.util.Random.nextInt(1000)}"
      
      // Close any existing connection pool to avoid conflicts
      try {
        ConnectionPool.close()
      } catch {
        case _: Exception => // Ignore if no pool exists
      }
      
      ConnectionPool.singleton(s"jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", "sa", "")
      logger.info(s"Using in-memory H2 database for testing: $dbName")
    } else {
      // Create data directory if it doesn't exist
      val dataDir = new java.io.File("./data")
      if (!dataDir.exists()) {
        dataDir.mkdirs()
        logger.info("Created data directory: ./data")
      }
      
      // Only set up connection pool if not already configured
      try {
        ConnectionPool.get()
        logger.info("Using existing H2 database connection")
      } catch {
        case _: Exception =>
          ConnectionPool.singleton("jdbc:h2:./data/tunic", "sa", "")
          logger.info("Using persistent H2 database: ./data/tunic.mv.db")
      }
    }
    
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
    
    logger.info("Database tables initialized successfully")
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
  ): Unit = {
    logger.debug(s"Inserting/updating file: $id ($filename) - status: $status")
    implicit val session: DBSession = AutoSession
    sql"""
      MERGE INTO files (id, filename, file_size, file_type, checksum, file_content, upload_timestamp, processing_status, error_message)
      VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(), ?, ?)
    """.bind(id, filename, size, fileType, checksum, content, status, error.orNull).update.apply()
  }

  def getFile(fileId: String): Option[DbFileRow] = {
    logger.debug(s"Getting file: $fileId")
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
  ): Unit = {
    logger.debug(s"Inserting extraction: $id for file $fileId - category: $category")
    implicit val session: DBSession = AutoSession
    sql"""
      INSERT INTO extractions
      (id, file_id, category, confidence_score, extracted_data_json, extraction_timestamp, model_used)
      VALUES
      (?, ?, ?, ?, ?, CURRENT_TIMESTAMP(), ?)
    """.bind(id, fileId, category, BigDecimal(confidence).setScale(3, BigDecimal.RoundingMode.HALF_UP), dataJson, modelUsed)
      .update.apply()
  }

  def fetchExtraction(fileId: String): Option[DbExtractionRow] = {
    logger.debug(s"Fetching extraction for file: $fileId")
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

  def listFiles(limit: Int = 100): List[FileSummary] = {
    implicit val session: DBSession = ReadOnlyAutoSession
    sql"""
      SELECT f.id,
             f.filename,
             f.upload_timestamp,
             f.processing_status,
             (
               SELECT e.category
               FROM extractions e
               WHERE e.file_id = f.id
               ORDER BY e.extraction_timestamp DESC
               LIMIT 1
             ) AS latest_category
      FROM files f
      ORDER BY f.upload_timestamp DESC
      LIMIT ?
    """.bind(limit).map { rs =>
      FileSummary(
        id = rs.string("id"),
        filename = rs.string("filename"),
        uploadedAt = rs.localDateTime("upload_timestamp").atZone(ZoneId.systemDefault()).toInstant,
        status = rs.string("processing_status"),
        category = Option(rs.string("latest_category"))
      )
    }.list.apply()
  }
}

object DatabaseService {
  val instance = new DatabaseService()
}