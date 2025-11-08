package routes

import org.scalatra.ScalatraServlet
import org.scalatra.servlet.{FileUploadSupport, MultipartConfig}
import org.slf4j.LoggerFactory
import services.{FileService, ExtractionService, LlmService, DatabaseService}
import models.api._
import models.api.ApiModels._
import config.AppConfig
import io.circe.syntax._
import io.circe.Json
import javax.servlet.http.HttpServletRequest
import java.io.InputStream
import scala.util.{Try, Success, Failure}

class FileRoutes(
  fileService: FileService,
  dbService: DatabaseService,
  extractionService: ExtractionService
) extends ScalatraServlet with FileUploadSupport {
  
  private val logger = LoggerFactory.getLogger(getClass)
  
  // Configure multipart support
  configureMultipartHandling(MultipartConfig(
    maxFileSize = Some(50 * 1024 * 1024), // 50MB max file size
    fileSizeThreshold = Some(1024 * 1024)  // 1MB threshold
  ))
  
  // Enable CORS for frontend
  before() {
    response.setHeader("Access-Control-Allow-Origin", "*")
    response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
    response.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
  }
  
  // Handle OPTIONS requests
  options("/*") {
    response.setHeader("Access-Control-Allow-Origin", "*")
    response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
    response.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
  }

  // Global JSON error handler
  error {
    case e: Throwable =>
      logger.error("Unhandled error", e)
      contentType = "application/json"
      halt(400, Json.obj(
        "error" -> Json.fromBoolean(true),
        "message" -> Json.fromString(getUserFriendlyErrorMessage(e))
      ).noSpaces)
  }
  
  // Test route to verify mounting
  get("/test") {
    logger.info("=== TEST ROUTE HIT ===")
    contentType = "text/plain"
    "FileRoutes is working!"
  }
  
  // List recent files
  get("/files") {
    contentType = "application/json"
    Try {
      val files = dbService.listFiles(200)
      import io.circe.generic.auto._
      files.asJson.noSpaces
    } match {
      case Success(json) => json
      case Failure(e) =>
        logger.error("Failed to list files", e)
        halt(500, Json.obj("error" -> Json.fromString("Failed to list files")))
    }
  }

  // Upload endpoint
  post("/files/upload") {
    logger.info("=== UPLOAD ENDPOINT HIT ===")
    contentType = "application/json"
    
    Try {
      fileParams.get("file") match {
        case Some(file) =>
          logger.info(s"Processing upload: ${file.name} (${file.size} bytes)")
          
          val filename = file.name
          val bytes = file.get()
          val contentType = file.contentType.getOrElse("application/octet-stream")
          
          // Store file metadata
          val meta = fileService.storeUploadedFile(filename, bytes, contentType)
          
          // Process with LLM
          val result = extractionService.processFile(meta, bytes)
          
          logger.info(s"Upload processed successfully: $filename -> ${result.category}")
          result.asJson.noSpaces
          
        case None =>
          val error = Json.obj(
            "error" -> Json.fromBoolean(true),
            "message" -> Json.fromString("No file was uploaded. Please select a file and try again.")
          )
          halt(400, error.noSpaces)
      }
    } match {
      case Success(result) => result
      case Failure(error) =>
        logger.error(s"Upload failed: ${error.getMessage}", error)
        val friendlyMessage = getUserFriendlyErrorMessage(error)
        val errorResponse = Json.obj(
          "error" -> Json.fromBoolean(true),
          "message" -> Json.fromString(friendlyMessage)
        )
        halt(400, errorResponse.noSpaces)
    }
  }
  
  // Get file result endpoint
  get("/files/:fileId") {
    contentType = "application/json"
    
    val fileId = params("fileId")
    
    Try {
      extractionService.fetchResult(fileId) match {
        case Some(result) => 
          logger.debug(s"Retrieved result for file: $fileId")
          result.asJson.noSpaces
        case None =>
          val error = Json.obj("message" -> Json.fromString("Not found"))
          halt(404, error.noSpaces)
      }
    } match {
      case Success(result) => result
      case Failure(error) =>
        logger.error(s"Failed to get file result: ${error.getMessage}", error)
        halt(500, Json.obj("error" -> Json.fromString(error.getMessage)).noSpaces)
    }
  }
  
  // Download file endpoint
  get("/files/:fileId/download") {
    val fileId = params("fileId")
    // Read from DB (persistent), not in-memory store, to survive restarts
    dbService.getFile(fileId) match {
      case Some(f) =>
        logger.debug(s"Serving download for file: $fileId")
        val mime = f.fileType.toLowerCase match {
          case "pdf" => "application/pdf"
          case "png" => "image/png"
          case "jpeg" | "jpg" => "image/jpeg"
          case _ => "application/octet-stream"
        }
        response.setHeader("Content-Type", mime)
        response.setHeader("Content-Disposition", s"attachment; filename=\"${f.filename}\"")
        f.content
      case None => halt(404, "File not found")
    }
  }
  
  def getUserFriendlyErrorMessage(error: Throwable): String = {
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
      case msg if msg.contains("Unique index or primary key violation") =>
        "This file is already being processed. Please wait for the current processing to complete."
      case msg if msg.toLowerCase.contains("missing choices") || msg.toLowerCase.contains("no text content") =>
        "AI service returned an unexpected format. Please try again in a moment."
      case msg if msg.toLowerCase.contains("openrouter error") =>
        "AI service error. Please try again shortly."
      case _ =>
        s"Unable to process this document: ${Option(error.getMessage).getOrElse("unknown error").take(120)}"
    }
  }
}