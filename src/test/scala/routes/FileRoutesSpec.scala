package routes

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import services.{FileService, LlmService, DatabaseService, ExtractionService}
import config.AppConfig
import org.mockito.Mockito._
import org.scalatestplus.mockito.MockitoSugar

class FileRoutesSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  // Create mock services for testing
  val mockFileService = mock[FileService]
  val mockLlmService = mock[LlmService]
  val mockDbService = mock[DatabaseService]
  val mockExtractionService = mock[ExtractionService]

  val fileRoutes = new FileRoutes(mockFileService, mockLlmService, mockDbService, mockExtractionService)

  "FileRoutes error handling" should "return user-friendly messages for API key errors" in {
    val error = new RuntimeException("OPENROUTER_API_KEY environment variable not set")
    val friendlyMessage = fileRoutes.getUserFriendlyErrorMessage(error)
    friendlyMessage should include("API configuration error")
  }

  it should "return user-friendly messages for categorization errors" in {
    val error = new RuntimeException("Failed to parse categorization response")
    val friendlyMessage = fileRoutes.getUserFriendlyErrorMessage(error)
    friendlyMessage should include("Unable to categorize this document")
  }

  it should "return user-friendly messages for extraction errors" in {
    val error = new RuntimeException("Failed to parse extraction response")
    val friendlyMessage = fileRoutes.getUserFriendlyErrorMessage(error)
    friendlyMessage should include("Unable to extract content")
  }

  it should "return user-friendly messages for timeout errors" in {
    val error = new RuntimeException("Request timeout occurred")
    val friendlyMessage = fileRoutes.getUserFriendlyErrorMessage(error)
    friendlyMessage should include("Document processing timed out")
  }

  it should "return user-friendly messages for rate limit errors" in {
    val error = new RuntimeException("HTTP 429 Too Many Requests")
    val friendlyMessage = fileRoutes.getUserFriendlyErrorMessage(error)
    friendlyMessage should include("Service is busy")
  }

  it should "return user-friendly messages for server errors" in {
    val error = new RuntimeException("HTTP 500 Internal Server Error")
    val friendlyMessage = fileRoutes.getUserFriendlyErrorMessage(error)
    friendlyMessage should include("AI service is temporarily unavailable")
  }

  it should "return user-friendly messages for duplicate file errors" in {
    val error = new RuntimeException("Unique index or primary key violation")
    val friendlyMessage = fileRoutes.getUserFriendlyErrorMessage(error)
    friendlyMessage should include("This file is already being processed")
  }

  it should "return generic message for unknown errors" in {
    val error = new RuntimeException("Some unexpected error occurred")
    val friendlyMessage = fileRoutes.getUserFriendlyErrorMessage(error)
    friendlyMessage should include("Unable to process this document")
  }
}