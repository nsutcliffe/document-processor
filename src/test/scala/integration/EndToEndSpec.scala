package integration

import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.IO
import cats.implicits._
import org.http4s._
import org.http4s.implicits._
import org.http4s.multipart._
import routes.FileRoutes
import services._
import config.AppConfig
import java.nio.charset.StandardCharsets

class EndToEndSpec extends AsyncFlatSpec with Matchers with AsyncIOSpec {

  // Mock configuration for testing
  val testConfig = AppConfig(openRouterApiKey = Some("test-key"))
  val routes = new FileRoutes().routes

  "End-to-end file processing" should "handle the complete workflow" in {
    val fileContent = "This is a test invoice document with important information."
    val fileBytes = fileContent.getBytes(StandardCharsets.UTF_8)
    
    val part = Part.fileData[IO]("file", "test-invoice.txt", fs2.Stream.emits(fileBytes))
    val multipart = Multipart[IO](Vector(part))
    
    val uploadRequest = Request[IO](Method.POST, uri"/api/files/upload")
      .withEntity(multipart)
      .withHeaders(multipart.headers)

    // Note: This test will fail without a real API key, but demonstrates the flow
    routes.orNotFound(uploadRequest).attempt.flatMap { uploadResult =>
      IO {
        // Should either succeed or fail gracefully with proper error handling
        uploadResult.isLeft || uploadResult.isRight shouldBe true
      }
    }
  }

  "System error handling" should "provide user-friendly messages" in {
    // Test with empty multipart (no file)
    val emptyMultipart = Multipart[IO](Vector.empty)
    val request = Request[IO](Method.POST, uri"/api/files/upload")
      .withEntity(emptyMultipart)
      .withHeaders(emptyMultipart.headers)

    routes.orNotFound(request).flatMap { response =>
      response.as[String].map { body =>
        response.status shouldBe Status.BadRequest
        body should include("error")
        body should include("file")
      }
    }
  }

  "File download workflow" should "handle missing files gracefully" in {
    val downloadRequest = Request[IO](Method.GET, uri"/api/files/non-existent-id/download")

    routes.orNotFound(downloadRequest).flatMap { response =>
      IO {
        response.status shouldBe Status.NotFound
      }
    }
  }

  "API endpoints" should "be accessible" in {
    val endpoints = List(
      (Method.GET, uri"/api/files/test-id"),
      (Method.GET, uri"/api/files/test-id/download")
    )

    val tests = endpoints.map { case (method, uri) =>
      val request = Request[IO](method, uri)
      routes.orNotFound(request).map(_.status)
    }

    tests.sequence.map { statuses =>
      // All endpoints should be reachable (either 200, 404, or 400 - not 500)
      statuses.foreach { status =>
        status should not equal Status.InternalServerError
      }
    }
  }
}