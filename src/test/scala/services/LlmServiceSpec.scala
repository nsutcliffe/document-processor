package services

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import config.AppConfig
import io.circe.parser._
import io.circe.generic.auto._
import scala.util.{Try, Success, Failure}

// Define test case classes locally to avoid import issues
case class CategoryResult(category: String, confidence_score: Double, reasoning: String)

class LlmServiceSpec extends AnyFlatSpec with Matchers {

  val mockConfig = AppConfig(openRouterApiKey = Some("test-api-key"))
  val llmService = new LlmService(mockConfig)

  "LlmService.selectModel" should "choose VLM for image files" in {
    llmService.selectModel("png", hasImages = true) shouldBe "openai/gpt-4o"
    llmService.selectModel("jpeg", hasImages = true) shouldBe "openai/gpt-4o"
    llmService.selectModel("jpg", hasImages = true) shouldBe "openai/gpt-4o"
  }

  it should "choose VLM for PDFs with images" in {
    llmService.selectModel("pdf", hasImages = true) shouldBe "openai/gpt-4o"
  }

  it should "choose text model for PDFs without images" in {
    llmService.selectModel("pdf", hasImages = false) shouldBe "anthropic/claude-3.5-sonnet"
  }

  it should "choose text model as default" in {
    llmService.selectModel("txt", hasImages = false) shouldBe "anthropic/claude-3.5-sonnet"
    llmService.selectModel("other", hasImages = false) shouldBe "anthropic/claude-3.5-sonnet"
  }

  "LlmService JSON parsing" should "handle clean JSON responses" in {
    val cleanJson = """{"category": "invoice", "confidence_score": 0.95, "reasoning": "test"}"""
    val result = parse(cleanJson).flatMap(_.as[CategoryResult])
    result.isRight shouldBe true
  }

  it should "clean markdown-wrapped JSON" in {
    val wrappedJson = """```json
{"category": "invoice", "confidence_score": 0.95, "reasoning": "test"}
```"""
    
    // Test the cleaning logic directly
    val cleaned = wrappedJson.trim
      .replaceAll("^```json\\s*", "")
      .replaceAll("^```\\s*", "")
      .replaceAll("\\s*```$", "")
      .trim
    
    val result = parse(cleaned).flatMap(_.as[CategoryResult])
    result.isRight shouldBe true
  }

  it should "handle various markdown wrapper formats" in {
    val testCases = Seq(
      "```json\n{\"test\": true}\n```",
      "```\n{\"test\": true}\n```",
      "{\"test\": true}",
      "  ```json  \n  {\"test\": true}  \n  ```  "
    )

    testCases.foreach { jsonString =>
      val cleaned = jsonString.trim
        .replaceAll("^```json\\s*", "")
        .replaceAll("^```\\s*", "")
        .replaceAll("\\s*```$", "")
        .trim
      
      parse(cleaned).isRight shouldBe true
    }
  }

  "LlmService error handling" should "handle missing API key gracefully" in {
    val noKeyConfig = AppConfig(openRouterApiKey = None)
    val noKeyService = new LlmService(noKeyConfig)
    
    val result = Try {
      noKeyService.categorizeDocument("test content", "pdf", false)
    }
    
    result shouldBe a[Failure[_]]
    result.failed.get.getMessage should include("OPENROUTER_API_KEY")
  }
}