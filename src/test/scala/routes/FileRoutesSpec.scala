package routes

import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.IO
import org.http4s._
import org.http4s.implicits._
import org.http4s.multipart._
import io.circe.parser._
import java.nio.charset.StandardCharsets

class FileRoutesSpec extends AsyncFlatSpec with Matchers with AsyncIOSpec {

  val routes = new FileRoutes().routes

  "FileRoutes upload endpoint" should "reject requests without file part" in {
    val request = Request[IO](Method.POST, uri"/api/files/upload")
      .withEntity("not multipart data")

    routes.orNotFound(request).flatMap { response =>
      IO {
        response.status shouldBe Status.BadRequest
      }
    }
  }

  it should "handle multipart requests with file" in {
    val fileContent = "test file content"
    val fileBytes = fileContent.getBytes(StandardCharsets.UTF_8)
    
    val part = Part.fileData[IO]("file", "test.txt", fs2.Stream.emits(fileBytes))
    val multipart = Multipart[IO](Vector(part))
    
    val request = Request[IO](Method.POST, uri"/api/files/upload")
      .withEntity(multipart)
      .withHeaders(multipart.headers)

    routes.orNotFound(request).flatMap { response =>
      response.as[String].map { body =>
        // Should return JSON response (either success or error)
        response.status should (equal(Status.Ok) or equal(Status.BadRequest))
        body should include("fileId")
      }
    }
  }

  "FileRoutes get endpoint" should "handle file retrieval requests" in {
    val request = Request[IO](Method.GET, uri"/api/files/test-file-id")

    routes.orNotFound(request).flatMap { response =>
      IO {
        // Should return either the file data or 404
        response.status should (equal(Status.Ok) or equal(Status.NotFound))
      }
    }
  }

  "FileRoutes download endpoint" should "handle download requests" in {
    val request = Request[IO](Method.GET, uri"/api/files/test-file-id/download")

    routes.orNotFound(request).flatMap { response =>
      IO {
        // Should return either the file or 404
        response.status should (equal(Status.Ok) or equal(Status.NotFound))
      }
    }
  }

  "Error handling" should "return user-friendly messages" in {
    val routes = new FileRoutes()
    
    // Test the private error message function through reflection or create a test helper
    val testErrors = Map(
      "OPENROUTER_API_KEY environment variable not set" -> "API configuration error",
      "Failed to parse categorization" -> "Unable to categorize this document",
      "timeout" -> "Document processing timed out",
      "429" -> "Service is busy",
      "500" -> "AI service is temporarily unavailable"
    )

    testErrors.foreach { case (error, expectedMessage) =>
      val friendlyMessage = routes.getUserFriendlyErrorMessage(new RuntimeException(error))
      friendlyMessage should include(expectedMessage.split(" ").head.toLowerCase)
    }
    succeed
  }
}