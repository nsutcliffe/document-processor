package services

import cats.effect.IO
import cats.implicits._
import models.api._
import models.api.ApiModels._
import config.AppConfig
import utils.FileUtils
import java.util.UUID
import io.circe.syntax._
import io.circe.{Decoder}

class ExtractionService(llmService: LlmService, dbService: DatabaseService) {
  
  def processFile(meta: StoredFileMeta, bytes: Array[Byte]): IO[UploadResponse] = {
    for {
      // Check if file already exists (avoid duplicate processing)
      existingFile <- dbService.getFile(meta.id)
      result <- existingFile match {
        case Some(file) if file.status == "completed" =>
          // File already processed, return existing results
          fetchResult(meta.id).map(_.getOrElse(createErrorResponse(meta, "File processed but results not found")))
        
        case Some(file) if file.status == "processing" =>
          // File is currently being processed, return processing status
          IO.pure(createProcessingResponse(meta))
        
        case _ =>
          // Process new file
          processNewFile(meta, bytes)
      }
    } yield result
  }

  private def processNewFile(meta: StoredFileMeta, bytes: Array[Byte]): IO[UploadResponse] = {
    for {
      // Detect file type and check for images
      fileType <- IO.pure(FileUtils.detectFileType(bytes, meta.filename))
      hasImages <- FileUtils.hasImages(bytes, fileType)
      
      // Store file in database (only if not exists)
      _ <- dbService.insertFile(
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
      _ <- IO(println(s"[DEBUG] Processing file: ${meta.filename}, detected type: $fileType, hasImages: $hasImages"))
      result <- if (fileType == "png" || fileType == "jpeg" || fileType == "jpg" || fileType == "image") {
        IO(println(s"[DEBUG] Using IMAGE processing flow for $fileType file")) *>
        processWithLLMImage(meta.id, bytes, fileType)
      } else {
        IO(println(s"[DEBUG] Using TEXT processing flow for $fileType file")) *>
        (for {
          // Extract text content for non-image files
          textContent <- FileUtils.extractTextContent(bytes, fileType)
          result <- processWithLLM(meta.id, textContent, fileType, hasImages)
        } yield result)
      }
      
    } yield result
  }.handleErrorWith { error =>
    // Store error in database and return user-friendly error response
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
    ).as(createErrorResponse(meta, userFriendlyMessage))
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
      downloadUrl = s"/api/files/${meta.id}/download"
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

  private def processWithLLMImage(fileId: String, imageBytes: Array[Byte], fileType: String): IO[UploadResponse] = {
    for {
      _ <- IO(println(s"[DEBUG] processWithLLMImage: Processing $fileType image with ${imageBytes.length} bytes"))
      // Get categorization and extraction in parallel for images
      categoryResult <- llmService.categorizeDocument(imageBytes, fileType)
      _ <- IO(println(s"[DEBUG] processWithLLMImage: Got categorization result: ${categoryResult.category}"))
      extractionResult <- llmService.extractContent(imageBytes, fileType)
      _ <- IO(println(s"[DEBUG] processWithLLMImage: Got extraction result with ${extractionResult.entities.size} entities"))
      
      // Convert to API models
      entities = extractionResult.entities.map(e => EntityDto(e.`type`, e.value, Some(e.confidence)))
      tables = extractionResult.tables.map(t => TableDto(t.table_name, t.headers, t.rows))
      
      // Store extraction in database
      _ <- dbService.insertExtraction(
        fileId + "-extraction",
        fileId,
        categoryResult.category,
        categoryResult.confidence_score,
        extractionResult.asJson.noSpaces,
        "gpt-4o" // Vision model used for images
      )
      
      // Update file status to completed
      _ <- dbService.insertFile(fileId, "", 0L, fileType, "", Array.empty[Byte], "completed", None)
      
    } yield UploadResponse(
      fileId = fileId,
      filename = "", // Will be filled from database
      fileSize = imageBytes.length.toLong,
      fileType = fileType,
      category = categoryResult.category,
      confidenceScore = categoryResult.confidence_score,
      entities = entities,
      dates = extractionResult.dates,
      tables = tables,
      downloadUrl = s"/api/files/$fileId/download"
    )
  }

  private def processWithLLM(fileId: String, content: String, fileType: String, hasImages: Boolean): IO[UploadResponse] = {
    for {
      // Get categorization and extraction in parallel
      categoryResult <- llmService.categorizeDocument(content, fileType, hasImages)
      extractionResult <- llmService.extractContent(content, fileType, hasImages)
      
      // Convert to API models
      entities = extractionResult.entities.map(e => EntityDto(e.`type`, e.value, Some(e.confidence)))
      tables = extractionResult.tables.map(t => TableDto(t.table_name, t.headers, t.rows))
      
      // Store extraction in database
      extractionId = UUID.randomUUID().toString
      extractionData = Map(
        "entities" -> entities.asJson,
        "dates" -> extractionResult.dates.asJson,
        "tables" -> tables.asJson
      ).asJson.noSpaces
      
      _ <- dbService.insertExtraction(
        extractionId,
        fileId,
        categoryResult.category,
        categoryResult.confidence_score,
        extractionData,
        llmService.selectModel(fileType, hasImages)
      )
      
      // Update file status to completed
      fileOpt <- dbService.getFile(fileId)
      _ <- fileOpt.fold(IO.unit) { file =>
        dbService.insertFile(
          file.id, file.filename, file.size, file.fileType, 
          file.checksum, file.content, "completed", None
        )
      }
      
    } yield UploadResponse(
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

  def fetchResult(fileId: String): IO[Option[UploadResponse]] = {
    for {
      fileOpt <- dbService.getFile(fileId)
      extractionOpt <- dbService.fetchExtraction(fileId)
    } yield {
      (fileOpt, extractionOpt).mapN { (file, extraction) =>
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
}



