package services

import cats.effect.{IO, Resource}
import cats.implicits._
import io.circe.generic.semiauto._
import org.http4s._
import org.http4s.client._
import org.http4s.ember.client._
import org.http4s.circe._
import io.circe._
import io.circe.parser._
import io.circe.syntax._
import config.AppConfig
import java.util.UUID
import java.util.Base64

final case class OpenRouterRequest(
  model: String,
  messages: List[OpenRouterMessage]
)

final case class OpenRouterMessage(
  role: String,
  content: Either[String, List[MessageContent]]
)

final case class MessageContent(
  `type`: String,
  text: Option[String] = None,
  image_url: Option[ImageUrl] = None
)

final case class ImageUrl(
  url: String,
  detail: Option[String] = None
)

final case class OpenRouterResponse(
  choices: List[OpenRouterChoice]
)

final case class OpenRouterChoice(
  message: OpenRouterMessage
)

final case class CategoryResult(
  category: String,
  confidence_score: Double,
  reasoning: String
)
object CategoryResult {
  implicit val categoryResultDecoder: Decoder[CategoryResult] = deriveDecoder[CategoryResult]
}

final case class ExtractionResult(
  entities: List[EntityResult],
  dates: List[String],
  tables: List[TableResult]
)
object ExtractionResult {
  implicit val extractionResultEncoder: Encoder[ExtractionResult] = deriveEncoder[ExtractionResult]
  implicit val extractionResultDecoder: Decoder[ExtractionResult] = deriveDecoder[ExtractionResult]
}

final case class EntityResult(
  `type`: String,
  value: String,
  confidence: Double
)
object EntityResult {
  implicit val entityResultEncoder: Encoder[EntityResult] = deriveEncoder[EntityResult]
  implicit val entityResultDecoder: Decoder[EntityResult] = deriveDecoder[EntityResult]
}

final case class TableResult(
  table_name: String,
  headers: List[String],
  rows: List[List[String]]
)
object TableResult {
  implicit val tableResultEncoder: Encoder[TableResult] = deriveEncoder[TableResult]
  implicit val tableResultDecoder: Decoder[TableResult] = deriveDecoder[TableResult]
}

class LlmService(config: AppConfig) {
  
  implicit val imageUrlEncoder: Encoder[ImageUrl] = Encoder.instance { imageUrl =>
    val fields = List(
      "url" -> Json.fromString(imageUrl.url)
    ) ++ imageUrl.detail.map(d => "detail" -> Json.fromString(d)).toList
    Json.obj(fields: _*)
  }
  implicit val messageContentEncoder: Encoder[MessageContent] = Encoder.instance { content =>
    val fields = List(
      "type" -> Json.fromString(content.`type`)
    ) ++ content.text.map(t => "text" -> Json.fromString(t)).toList ++
         content.image_url.map(u => "image_url" -> u.asJson).toList
    Json.obj(fields: _*)
  }
  implicit val messageEncoder: Encoder[OpenRouterMessage] = Encoder.instance { msg =>
    Json.obj(
      "role" -> Json.fromString(msg.role),
      "content" -> (msg.content match {
        case Left(text) => Json.fromString(text)
        case Right(contents) => contents.asJson
      })
    )
  }
  implicit val requestEncoder: Encoder[OpenRouterRequest] = Encoder.forProduct2("model", "messages")(r => (r.model, r.messages))
  implicit val imageUrlDecoder: Decoder[ImageUrl] = Decoder.forProduct2("url", "detail")(ImageUrl.apply)
  implicit val messageContentDecoder: Decoder[MessageContent] = Decoder.forProduct3("type", "text", "image_url")(MessageContent.apply)
  implicit val messageDecoder: Decoder[OpenRouterMessage] = Decoder.instance { cursor =>
    for {
      role <- cursor.get[String]("role")
      content <- cursor.get[String]("content").map(Left(_)).orElse(
        cursor.get[List[MessageContent]]("content").map(Right(_))
      )
    } yield OpenRouterMessage(role, content)
  }
  implicit val choiceDecoder: Decoder[OpenRouterChoice] = Decoder.forProduct1("message")(OpenRouterChoice.apply)
  implicit val responseDecoder: Decoder[OpenRouterResponse] = Decoder.forProduct1("choices")(OpenRouterResponse.apply)
  
  // Decoders are now in companion objects

  private val clientResource: Resource[IO, Client[IO]] = EmberClientBuilder.default[IO].build

  def selectModel(fileType: String, hasImages: Boolean): String = {
    (fileType.toLowerCase, hasImages) match {
      case (ft, _) if ft.contains("png") || ft.contains("jpeg") || ft.contains("jpg") => "openai/gpt-4o"
      case (ft, true) if ft.contains("pdf") => "openai/gpt-4o"
      case _ => "anthropic/claude-3.5-sonnet"
    }
  }

  def categorizeDocument(content: String, fileType: String, hasImages: Boolean): IO[CategoryResult] = {
    val model = selectModel(fileType, hasImages)
    val prompt = """You are a document classification system. Classify the document into:
- invoice
- marketplace_listing_screenshot
- chat_screenshot
- website_screenshot
- other

Return ONLY valid JSON exactly matching:
{
  "category": "category_name",
  "confidence_score": 0.95,
  "reasoning": "Brief explanation"
}"""

    callOpenRouter(model, prompt, content).flatMap { response =>
      // Debug logging
      IO(println(s"[DEBUG] Categorization response from $model: ${response.take(200)}...")) *>
      parseJson[CategoryResult](response).fold(
        error => {
          IO(println(s"[DEBUG] Failed to parse categorization response: $error")) *>
          IO(println(s"[DEBUG] Raw response: $response")) *>
          IO.raiseError(new RuntimeException(s"Failed to parse categorization: $error"))
        },
        result => {
          IO(println(s"[DEBUG] Successfully parsed categorization: ${result.category} (${result.confidence_score})")) *>
          IO.pure(result)
        }
      )
    }
  }

  def extractContent(content: String, fileType: String, hasImages: Boolean): IO[ExtractionResult] = {
    val model = selectModel(fileType, hasImages)
    val prompt = """Extract key information. Look for:
- Names (people, companies, organizations)
- Places/locations
- Phone numbers
- IP addresses
- Account IDs
- Payment methods (credit cards, bank accounts)
- Merchants
- Dates
- Tables (if present)

For each table, output:
{
  "table_name": "string",
  "headers": ["col1", "col2", ...],
  "rows": [["r1c1", "r1c2", ...], ["r2c1", "r2c2", ...]]
}

Return ONLY JSON exactly matching:
{
  "entities": [ {"type": "name|place|phone|ip_address|account_id|payment_method|merchant", "value": "...", "confidence": 0.0-1.0 }, ...],
  "dates": ["YYYY-MM-DD", ...],
  "tables": [ { "table_name": "...", "headers": [...], "rows": [[...], ...] } ]
}"""

    callOpenRouter(model, prompt, content).flatMap { response =>
      // Debug logging
      IO(println(s"[DEBUG] Extraction response from $model: ${response.take(200)}...")) *>
      parseJson[ExtractionResult](response).fold(
        error => {
          IO(println(s"[DEBUG] Failed to parse extraction response: $error")) *>
          IO(println(s"[DEBUG] Raw response: $response")) *>
          IO.raiseError(new RuntimeException(s"Failed to parse extraction: $error"))
        },
        result => {
          IO(println(s"[DEBUG] Successfully parsed extraction: ${result.entities.size} entities, ${result.dates.size} dates, ${result.tables.size} tables")) *>
          IO.pure(result)
        }
      )
    }
  }

  // Overloaded methods for handling images with raw bytes
  def categorizeDocument(imageBytes: Array[Byte], fileType: String): IO[CategoryResult] = {
    val model = selectModel(fileType, hasImages = true)
    val prompt = """You are a document classification AI. Analyze the uploaded image and classify it into one of these categories:
- invoice
- marketplace_listing_screenshot
- chat_screenshot
- website_screenshot
- other

IMPORTANT: You must respond with ONLY valid JSON in this exact format:
{
  "category": "category_name",
  "confidence_score": 0.95,
  "reasoning": "Brief explanation"
}

Do not include any other text, explanations, or apologies. Only return the JSON object."""

    IO(println(s"[DEBUG] categorizeDocument: Using model $model for $fileType image (${imageBytes.length} bytes)")) *>
    callOpenRouterWithImage(model, prompt, imageBytes, fileType).flatMap { response =>
      IO(println(s"[DEBUG] Categorization response from $model: ${response.take(200)}...")) *>
      parseJson[CategoryResult](response).fold(
        error => {
          IO(println(s"[DEBUG] Failed to parse categorization response: $error")) *>
          IO(println(s"[DEBUG] Raw response: $response")) *>
          IO.raiseError(new RuntimeException(s"Failed to parse categorization: $error"))
        },
        result => {
          IO(println(s"[DEBUG] Successfully parsed categorization: ${result.category} (${result.confidence_score})")) *>
          IO.pure(result)
        }
      )
    }
  }

  def extractContent(imageBytes: Array[Byte], fileType: String): IO[ExtractionResult] = {
    val model = selectModel(fileType, hasImages = true)
    val prompt = """You are a data extraction AI. Analyze the uploaded image and extract key information:

Look for:
- Names (people, companies, organizations)
- Places/locations  
- Phone numbers
- IP addresses
- Account IDs
- Payment methods (credit cards, bank accounts)
- Merchants
- Dates
- Tables (if present)

IMPORTANT: You must respond with ONLY valid JSON in this exact format:
{
  "entities": [ {"type": "name|place|phone|ip_address|account_id|payment_method|merchant", "value": "...", "confidence": 0.0-1.0 }, ...],
  "dates": ["YYYY-MM-DD", ...],
  "tables": [ { "table_name": "...", "headers": [...], "rows": [[...], ...] } ]
}

Do not include any other text, explanations, or apologies. Only return the JSON object. If you cannot process the image, return empty arrays for entities, dates, and tables."""

    IO(println(s"[DEBUG] extractContent: Using model $model for $fileType image (${imageBytes.length} bytes)")) *>
    callOpenRouterWithImage(model, prompt, imageBytes, fileType).flatMap { response =>
      IO(println(s"[DEBUG] Extraction response from $model: ${response.take(200)}...")) *>
      parseJson[ExtractionResult](response).fold(
        error => {
          IO(println(s"[DEBUG] Failed to parse extraction response: $error")) *>
          IO(println(s"[DEBUG] Raw response: $response")) *>
          IO.raiseError(new RuntimeException(s"Failed to parse extraction: $error"))
        },
        result => {
          IO(println(s"[DEBUG] Successfully parsed extraction: ${result.entities.size} entities, ${result.dates.size} dates, ${result.tables.size} tables")) *>
          IO.pure(result)
        }
      )
    }
  }

  private def callOpenRouterWithImage(model: String, systemPrompt: String, imageBytes: Array[Byte], fileType: String): IO[String] = {
    config.openRouterApiKey match {
      case None => IO.raiseError(new RuntimeException("OPENROUTER_API_KEY environment variable not set"))
      case Some(apiKey) =>
        val base64Image = Base64.getEncoder.encodeToString(imageBytes)
        val mimeType = fileType match {
          case "png" => "image/png"
          case "jpeg" | "jpg" => "image/jpeg"
          case _ => "image/jpeg" // default
        }
        val dataUrl = s"data:$mimeType;base64,$base64Image"
        
        val userMessage = OpenRouterMessage("user", Right(List(
          MessageContent("text", Some("Analyze this image and follow the system instructions exactly."), None),
          MessageContent("image_url", None, Some(ImageUrl(dataUrl)))
        )))
        
        val request = OpenRouterRequest(
          model = model,
          messages = List(
            OpenRouterMessage("system", Left(systemPrompt)),
            userMessage
          )
        )

        clientResource.use { client =>
          val uri = Uri.unsafeFromString("https://openrouter.ai/api/v1/chat/completions")
          val httpRequest = Request[IO](
            method = Method.POST,
            uri = uri,
            headers = Headers(
              Header.Raw(org.typelevel.ci.CIString("Authorization"), s"Bearer $apiKey"),
              Header.Raw(org.typelevel.ci.CIString("Content-Type"), "application/json"),
              Header.Raw(org.typelevel.ci.CIString("HTTP-Referer"), "http://localhost:8080"),
              Header.Raw(org.typelevel.ci.CIString("X-Title"), "Tunic Pay Document Processor")
            )
          ).withEntity(request.asJson)

          retryWithBackoff(client.expect[String](httpRequest), maxRetries = 3)
        }.flatMap { responseBody =>
          parseJson[OpenRouterResponse](responseBody) match {
            case Right(response) if response.choices.nonEmpty =>
              IO.pure(response.choices.head.message.content match {
                case Left(text) => text
                case Right(_) => "No text content in response"
              })
            case Right(_) =>
              IO.raiseError(new RuntimeException("Empty response from OpenRouter"))
            case Left(error) =>
              IO.raiseError(new RuntimeException(s"Failed to parse OpenRouter response: $error"))
          }
        }
    }
  }

  private def callOpenRouter(model: String, systemPrompt: String, userContent: String): IO[String] = {
    config.openRouterApiKey match {
      case None => IO.raiseError(new RuntimeException("OPENROUTER_API_KEY environment variable not set"))
      case Some(apiKey) =>
        val request = OpenRouterRequest(
          model = model,
          messages = List(
            OpenRouterMessage("system", Left(systemPrompt)),
            OpenRouterMessage("user", Left(userContent))
          )
        )

        clientResource.use { client =>
          val uri = Uri.unsafeFromString("https://openrouter.ai/api/v1/chat/completions")
          val httpRequest = Request[IO](
            method = Method.POST,
            uri = uri,
            headers = Headers(
              Header.Raw(org.typelevel.ci.CIString("Authorization"), s"Bearer $apiKey"),
              Header.Raw(org.typelevel.ci.CIString("Content-Type"), "application/json"),
              Header.Raw(org.typelevel.ci.CIString("HTTP-Referer"), "http://localhost:8080"),
              Header.Raw(org.typelevel.ci.CIString("X-Title"), "Tunic Pay Document Processor")
            )
          ).withEntity(request.asJson)

          retryWithBackoff(client.expect[String](httpRequest), maxRetries = 3)
        }.flatMap { responseBody =>
          parseJson[OpenRouterResponse](responseBody) match {
            case Right(response) if response.choices.nonEmpty =>
              IO.pure(response.choices.head.message.content match {
                case Left(text) => text
                case Right(_) => "No text content in response"
              })
            case Right(_) =>
              IO.raiseError(new RuntimeException("Empty response from OpenRouter"))
            case Left(error) =>
              IO.raiseError(new RuntimeException(s"Failed to parse OpenRouter response: $error"))
          }
        }
    }
  }

  private def retryWithBackoff[A](operation: IO[A], maxRetries: Int, currentRetry: Int = 0): IO[A] = {
    operation.handleErrorWith { error =>
      if (currentRetry < maxRetries && shouldRetry(error)) {
        IO.sleep(scala.concurrent.duration.Duration.fromNanos(1000000000L)) >> // 1 second delay
          retryWithBackoff(operation, maxRetries, currentRetry + 1)
      } else {
        IO.raiseError(error)
      }
    }
  }

  private def shouldRetry(error: Throwable): Boolean = {
    error.getMessage match {
      case msg if msg.contains("400") => 
        // Don't retry 400 errors, but log them for debugging
        println(s"[DEBUG] 400 Bad Request error (not retrying): $msg")
        false
      case msg if msg.contains("429") => true  // Rate limit
      case msg if msg.contains("408") => true  // Timeout
      case msg if msg.contains("5") => true    // 5xx server errors
      case _ => false
    }
  }

  private def parseJson[A: Decoder](jsonString: String): Either[String, A] = {
    // Clean the JSON string - remove markdown code blocks if present
    val cleanedJson = jsonString.trim
      .replaceAll("^```json\\s*", "")
      .replaceAll("^```\\s*", "")
      .replaceAll("\\s*```$", "")
      .trim
    
    parse(cleanedJson).flatMap(_.as[A]).left.map(_.getMessage)
  }
}

object LlmService {
  def apply(config: AppConfig): LlmService = new LlmService(config)
}