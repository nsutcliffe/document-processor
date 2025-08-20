package services

import models.api._
import models.api.ApiModels._
import config.AppConfig
import utils.FileUtils
import java.util.UUID
import io.circe.syntax._
import io.circe.{Decoder}
import org.slf4j.LoggerFactory
import scala.util.{Try, Success, Failure}

class ExtractionService(llmService: LlmService, dbService: DatabaseService) {
  
  private val logger = LoggerFactory.getLogger(getClass)
  
  def processFile(meta: StoredFileMeta, bytes: Array[Byte]): UploadResponse = {
    logger.info(s"Processing file: ${meta.filename} (${meta.size} bytes)")
    
    // Check if file already exists (avoid duplicate processing)
    dbService.getFile(meta.id) match {
      case Some(file) if file.status == "completed" =>
        // File already processed, return existing results
        logger.debug(s"File already processed: ${meta.id}")
        fetchResult(meta.id).getOrElse(createErrorResponse(meta, "File processed but results not found"))
      
      case Some(file) if file.status == "processing" =>
        // File is currently being processed, return processing status
        logger.debug(s"File currently processing: ${meta.id}")
        createProcessingResponse(meta)
      
      case _ =>
        // Process new file
        processNewFile(meta, bytes)
    }
  }

  private def processNewFile(meta: StoredFileMeta, bytes: Array[Byte]): UploadResponse = {
    Try {
      // Detect file type and check for images
      val fileType = FileUtils.detectFileType(bytes, meta.filename)
      val hasImages = FileUtils.hasImages(bytes, fileType)
      
      logger.debug(s"Processing file: ${meta.filename}, detected type: $fileType, hasImages: $hasImages")
      
      // Store file in database (only if not exists)
      dbService.insertFile(
        meta.id, 
        meta.filename, 
        meta.size, 
        fileType, 
        meta.checksum, 
        bytes, 
        "processing", 
        None
      )
      
      // Process with LLM (handle images vs text differently)
      val result = if (fileType == "png" || fileType == "jpeg" || fileType == "jpg" || fileType == "image") {
        logger.debug(s"Using IMAGE processing flow for $fileType file")
        try {
          processWithLLMImage(meta.id, bytes, fileType)
        } catch {
          case e: Throwable =>
            logger.warn(s"Vision processing failed, falling back to safe result: ${e.getMessage}")
            UploadResponse(
              fileId = meta.id,
              filename = meta.filename,
              fileSize = meta.size,
              fileType = meta.contentType,
              category = "other",
              confidenceScore = 0.0,
              entities = List.empty,
              dates = List.empty,
              tables = List.empty,
              downloadUrl = s"/api/files/${meta.id}/download",
              error = Some(true),
              message = Some("AI service error for this image. Please try again shortly or use a smaller image.")
            )
        }
      } else {
        logger.debug(s"Using TEXT processing flow for $fileType file")
        // Extract text content for non-image files
        val textContent = FileUtils.extractTextContent(bytes, fileType)
        processWithLLM(meta.id, textContent, fileType, hasImages)
      }
      
      result
      
    } match {
      case Success(result) => result
      case Failure(error) =>
        // Store error in database and return user-friendly error response
        logger.error(s"Failed to process file ${meta.filename}: ${error.getMessage}", error)
        val userFriendlyMessage = getUserFriendlyError(error)
        dbService.insertFile(
          meta.id, 
          meta.filename, 
          meta.size, 
          meta.contentType, 
          meta.checksum, 
          bytes, 
          "failed", 
          Some(error.getMessage)
        )
        createErrorResponse(meta, userFriendlyMessage)
    }
  }

  private def getUserFriendlyError(error: Throwable): String = {
    error.getMessage match {
      case msg if msg.contains("OPENROUTER_API_KEY") => 
        "API configuration error. Please contact support."
      case msg if msg.contains("Failed to parse categorization") => 
        "Unable to categorize this document. The AI service may be having issues."
      case msg if msg.contains("Failed to parse extraction") => 
        "Unable to extract content from this document. Please try a different file format."
      case msg if msg.contains("timeout") || msg.contains("TimeoutException") => 
        "Document processing timed out. Please try with a smaller file."
      case msg if msg.contains("429") => 
        "Service is busy. Please wait a moment and try again."
      case msg if msg.contains("5") && (msg.contains("50") || msg.contains("51") || msg.contains("52") || msg.contains("53")) => 
        "AI service is temporarily unavailable. Please try again later."
      case _ => 
        "Unable to process this document. Please check the file format and try again."
    }
  }

  private def createErrorResponse(meta: StoredFileMeta, message: String): UploadResponse = {
    UploadResponse(
      fileId = meta.id,
      filename = meta.filename,
      fileSize = meta.size,
      fileType = meta.contentType,
      category = "other",
      confidenceScore = 0.0,
      entities = List.empty,
      dates = List.empty,
      tables = List.empty,
      downloadUrl = s"/api/files/${meta.id}/download",
      error = Some(true),
      message = Some(message)
    )
  }

  private def createProcessingResponse(meta: StoredFileMeta): UploadResponse = {
    UploadResponse(
      fileId = meta.id,
      filename = meta.filename,
      fileSize = meta.size,
      fileType = meta.contentType,
      category = "processing",
      confidenceScore = 0.0,
      entities = List.empty,
      dates = List.empty,
      tables = List.empty,
      downloadUrl = s"/api/files/${meta.id}/download"
    )
  }

  private def processWithLLMImage(fileId: String, imageBytes: Array[Byte], fileType: String): UploadResponse = {
    logger.debug(s"processWithLLMImage: Processing $fileType image with ${imageBytes.length} bytes")
    
    // Get categorization and extraction for images
    val categoryResult = llmService.categorizeDocument(imageBytes, fileType)
    logger.debug(s"processWithLLMImage: Got categorization result: ${categoryResult.category}")
    
    val extractionResult = llmService.extractContent(imageBytes, fileType)
    logger.debug(s"processWithLLMImage: Got extraction result with ${extractionResult.entities.size} entities")
    
    // Convert to API models
    val entities = extractionResult.entities.map(e => EntityDto(e.`type`, e.value, Some(e.confidence)))
    val tables = extractionResult.tables.map(t => TableDto(t.table_name, t.headers, t.rows))
    
    // Store extraction in database
    dbService.insertExtraction(
      fileId + "-extraction",
      fileId,
      categoryResult.category,
      categoryResult.confidence_score,
      extractionResult.asJson.noSpaces,
      "gpt-4o" // Vision model used for images
    )
    
    // Update file status to completed without overwriting original bytes/metadata
    dbService.getFile(fileId) match {
      case Some(file) =>
        dbService.insertFile(
          file.id,
          file.filename,
          file.size,
          file.fileType,
          file.checksum,
          file.content,
          "completed",
          None
        )
      case None =>
        logger.warn(s"processWithLLMImage: file $fileId not found in DB when updating status")
    }
    
    val fileOpt = dbService.getFile(fileId)
    UploadResponse(
      fileId = fileId,
      filename = fileOpt.map(_.filename).getOrElse("unknown"),
      fileSize = fileOpt.map(_.size).getOrElse(imageBytes.length.toLong),
      fileType = fileType,
      category = categoryResult.category,
      confidenceScore = categoryResult.confidence_score,
      entities = entities,
      dates = extractionResult.dates,
      tables = tables,
      downloadUrl = s"/api/files/$fileId/download"
    )
  }

  private def processWithLLM(fileId: String, content: String, fileType: String, hasImages: Boolean): UploadResponse = {
    logger.debug(s"processWithLLM: Processing $fileType file with text content")
    
    // Get categorization and extraction
    val categoryResult = llmService.categorizeDocument(content, fileType, hasImages)
    val extractionResult = llmService.extractContent(content, fileType, hasImages)
    
    // Convert to API models
    val entities = extractionResult.entities.map(e => EntityDto(e.`type`, e.value, Some(e.confidence)))
    val tables = extractionResult.tables.map(t => TableDto(t.table_name, t.headers, t.rows))
    
    // Store extraction in database
    val extractionId = UUID.randomUUID().toString
    val extractionData = Map(
      "entities" -> entities.asJson,
      "dates" -> extractionResult.dates.asJson,
      "tables" -> tables.asJson
    ).asJson.noSpaces
    
    dbService.insertExtraction(
      extractionId,
      fileId,
      categoryResult.category,
      categoryResult.confidence_score,
      extractionData,
      llmService.selectModel(fileType, hasImages)
    )
    
    // Update file status to completed
    dbService.getFile(fileId) match {
      case Some(file) =>
        dbService.insertFile(
          file.id, file.filename, file.size, file.fileType, 
          file.checksum, file.content, "completed", None
        )
      case None =>
        logger.warn(s"Could not find file $fileId to update status")
    }
    
    val fileOpt = dbService.getFile(fileId)
    UploadResponse(
      fileId = fileId,
      filename = fileOpt.map(_.filename).getOrElse("unknown"),
      fileSize = fileOpt.map(_.size).getOrElse(0L),
      fileType = fileType,
      category = categoryResult.category,
      confidenceScore = categoryResult.confidence_score,
      entities = entities,
      dates = extractionResult.dates,
      tables = tables,
      downloadUrl = s"/api/files/$fileId/download"
    )
  }

  def fetchResult(fileId: String): Option[UploadResponse] = {
    logger.debug(s"Fetching result for file: $fileId")
    
    for {
      file <- dbService.getFile(fileId)
      extraction <- dbService.fetchExtraction(fileId)
    } yield {
      // Parse extraction data
      val extractionJson = io.circe.parser.parse(extraction.dataJson).getOrElse(io.circe.Json.Null)
      implicit val entityDecoder: Decoder[EntityDto] = Decoder.forProduct3("type", "value", "confidence")(EntityDto.apply _)
      implicit val tableDecoder: Decoder[TableDto] = Decoder.forProduct3("table_name", "headers", "rows")(TableDto.apply _)
      
      val entities = extractionJson.hcursor.downField("entities").as[List[EntityDto]].getOrElse(List.empty)
      val dates = extractionJson.hcursor.downField("dates").as[List[String]].getOrElse(List.empty)
      val tables = extractionJson.hcursor.downField("tables").as[List[TableDto]].getOrElse(List.empty)
      
      UploadResponse(
        fileId = file.id,
        filename = file.filename,
        fileSize = file.size,
        fileType = file.fileType,
        category = extraction.category,
        confidenceScore = extraction.confidence,
        entities = entities,
        dates = dates,
        tables = tables,
        downloadUrl = s"/api/files/${file.id}/download"
      )
    }
  }
}