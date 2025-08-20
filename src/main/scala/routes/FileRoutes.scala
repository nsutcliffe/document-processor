package routes

import cats.effect._
import cats.implicits._
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.circe._
import org.http4s.multipart._
import io.circe.syntax._
import io.circe.Json
import services.{FileService, ExtractionService, LlmService, DatabaseService}
import models.api._
import models.api.ApiModels._
import org.typelevel.ci.CIString
import config.AppConfig

final class FileRoutes()(implicit cs: Concurrent[IO]) {
  private val config = AppConfig.default
  private val fileService = new FileService
  private val llmService = LlmService(config)
  private val dbService = DatabaseService.instance
  private val extractionService = new ExtractionService(llmService, dbService)

  private val uploadRoute: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ POST -> Root / "api" / "files" / "upload" =>
      req.decode[Multipart[IO]] { m =>
        val maybeFilePart = m.parts.find(_.name.contains("file"))
        maybeFilePart match {
          case Some(part) =>
            (for {
              bytes <- part.body.compile.to(Array)
              meta  <- fileService.storeUploadedFile(part.filename.getOrElse("uploaded"), bytes, part.headers)
              result <- extractionService.processFile(meta, bytes)
              resp   <- Ok(result.asJson)
            } yield resp).handleErrorWith { error =>
              // Log the error and return user-friendly message
              IO(println(s"Upload error: ${error.getMessage}")) *>
              BadRequest(Json.obj(
                "error" -> Json.fromBoolean(true),
                "message" -> Json.fromString(getUserFriendlyErrorMessage(error))
              ))
            }
          case None => BadRequest(Json.obj(
            "error" -> Json.fromBoolean(true),
            "message" -> Json.fromString("No file was uploaded. Please select a file and try again.")
          ))
        }
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
      case _ => 
        s"Unable to process this document: ${error.getMessage.take(100)}..."
    }
  }

  private val getRoute: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "api" / "files" / fileId =>
      for {
        res <- extractionService.fetchResult(fileId)
        resp <- res.fold(NotFound(Json.obj("message" -> Json.fromString("Not found"))))(r => Ok(r.asJson))
      } yield resp
  }

  private val downloadRoute: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "api" / "files" / fileId / "download" =>
      fileService.fetchFile(fileId).flatMap {
        case None => NotFound()
        case Some(f) =>
          Ok(f.content).map(_.putHeaders(
            Header.Raw(CIString("Content-Type"), f.contentType),
            Header.Raw(CIString("Content-Disposition"), s"attachment; filename=\"${f.filename}\"")
          ))
      }
  }

  val routes: HttpRoutes[IO] = uploadRoute <+> getRoute <+> downloadRoute
}


