package services

import io.circe.generic.semiauto._
import io.circe._
import io.circe.parser._
import io.circe.syntax._
import config.AppConfig
import java.util.Base64
import org.slf4j.LoggerFactory
import scala.util.{Try, Success, Failure}

class LlmService(config: AppConfig) {
  
  private val logger = LoggerFactory.getLogger(getClass)

  def selectModel(fileType: String, hasImages: Boolean): String = {
    (fileType.toLowerCase, hasImages) match {
      case (ft, _) if ft.contains("png") || ft.contains("jpeg") || ft.contains("jpg") => "openai/gpt-4o"
      case (ft, true) if ft.contains("pdf") => "openai/gpt-4o"
      case _ => "anthropic/claude-3.5-sonnet"
    }
  }

  def categorizeDocument(content: String, fileType: String, hasImages: Boolean): CategoryResult = {
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

    val response = callOpenRouter(model, prompt, content)
    logger.debug(s"Categorization response from $model: ${response.take(200)}...")
    
    parseJson[CategoryResult](response) match {
      case Right(result) =>
        logger.debug(s"Successfully parsed categorization: ${result.category} (${result.confidence_score})")
        result
      case Left(_) =>
        // Attempt a one-shot format correction
        val schemaPrompt =
          """You MUST output ONLY valid JSON matching exactly this schema:
          {
            "category": "invoice|marketplace_listing_screenshot|chat_screenshot|website_screenshot|other",
            "confidence_score": number,
            "reasoning": "string"
          }
          Do not include any extra text.
          """.stripMargin
        val corrected = callOpenRouter(model, schemaPrompt, response)
        parseJson[CategoryResult](corrected) match {
          case Right(r2) => r2
          case Left(err2) =>
            logger.error(s"Failed to parse categorization after correction: $err2")
            throw new RuntimeException(s"Failed to parse categorization: $err2")
        }
    }
  }

  def extractContent(content: String, fileType: String, hasImages: Boolean): ExtractionResult = {
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

Return ONLY JSON exactly matching:
{
  "entities": [ {"type": "name|place|phone|ip_address|account_id|payment_method|merchant", "value": "...", "confidence": 0.0-1.0 }, ...],
  "dates": ["YYYY-MM-DD", ...],
  "tables": [ { "table_name": "...", "headers": [...], "rows": [[...], ...] } ]
}"""

    val response = callOpenRouter(model, prompt, content)
    logger.debug(s"Extraction response from $model: ${response.take(200)}...")
    
    parseJson[ExtractionResult](response) match {
      case Right(result) =>
        logger.debug(s"Successfully parsed extraction: ${result.entities.size} entities, ${result.dates.size} dates, ${result.tables.size} tables")
        result
      case Left(_) =>
        // Attempt a one-shot format correction
        val schemaPrompt =
          """The previous assistant response was not valid JSON.
          You MUST output ONLY valid JSON matching exactly this schema:
          {
            "entities": [ { "type": "name|place|phone|ip_address|account_id|payment_method|merchant", "value": "string", "confidence": number }, ...],
            "dates": ["YYYY-MM-DD", ...],
            "tables": [ { "table_name": "string", "headers": ["..."], "rows": [["..."], ["..."]] } ]
          }
          If you cannot extract anything, return empty arrays for all fields.
          Do not include any extra text.
          """.stripMargin
        val corrected = callOpenRouter(model, schemaPrompt, response)
        parseJson[ExtractionResult](corrected) match {
          case Right(r2) => r2
          case Left(err2) =>
            logger.error(s"Failed to parse extraction after correction: $err2")
            throw new RuntimeException(s"Failed to parse extraction: $err2")
        }
    }
  }

  // Overloaded methods for handling images with raw bytes
  def categorizeDocument(imageBytes: Array[Byte], fileType: String): CategoryResult = {
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

    logger.debug(s"categorizeDocument: Using model $model for $fileType image (${imageBytes.length} bytes)")
    val response = callOpenRouterWithImage(model, prompt, imageBytes, fileType)
    logger.debug(s"Categorization response from $model: ${response.take(200)}...")
    
    parseJson[CategoryResult](response) match {
      case Right(result) =>
        logger.debug(s"Successfully parsed categorization: ${result.category} (${result.confidence_score})")
        result
      case Left(error) =>
        logger.error(s"Failed to parse categorization response: $error")
        logger.debug(s"Raw response: $response")
        throw new RuntimeException(s"Failed to parse categorization: $error")
    }
  }

  def extractContent(imageBytes: Array[Byte], fileType: String): ExtractionResult = {
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

    logger.debug(s"extractContent: Using model $model for $fileType image (${imageBytes.length} bytes)")
    val response = callOpenRouterWithImage(model, prompt, imageBytes, fileType)
    logger.debug(s"Extraction response from $model: ${response.take(200)}...")
    
    parseJson[ExtractionResult](response) match {
      case Right(result) =>
        logger.debug(s"Successfully parsed extraction: ${result.entities.size} entities, ${result.dates.size} dates, ${result.tables.size} tables")
        result
      case Left(error) =>
        logger.error(s"Failed to parse extraction response: $error")
        logger.debug(s"Raw response: $response")
        throw new RuntimeException(s"Failed to parse extraction: $error")
    }
  }

  private def callOpenRouterWithImage(model: String, systemPrompt: String, imageBytes: Array[Byte], fileType: String): String = {
    config.openRouterApiKey match {
      case None => throw new RuntimeException("OPENROUTER_API_KEY environment variable not set")
      case Some(apiKey) =>
        // Preprocess image to reduce provider errors
        val (preparedBytes, preparedMime, detailHint) = utils.ImageUtils.prepareImageForVision(imageBytes, fileType)
        val base64Image = Base64.getEncoder.encodeToString(preparedBytes)
        val mimeType = preparedMime
        val dataUrl = s"data:$mimeType;base64,$base64Image"
        
        val userMessage = OpenRouterMessage("user", Right(List(
          MessageContent("text", Some("Analyze this image and follow the system instructions exactly."), None),
          MessageContent("image_url", None, Some(ImageUrl(dataUrl, Some(detailHint))))
        )))
        
        val request = OpenRouterRequest(
          model = model,
          messages = List(
            OpenRouterMessage("system", Left(systemPrompt)),
            userMessage
          )
        )

        callOpenRouterAPI(request, apiKey)
    }
  }

  private def callOpenRouter(model: String, systemPrompt: String, userContent: String): String = {
    config.openRouterApiKey match {
      case None => throw new RuntimeException("OPENROUTER_API_KEY environment variable not set")
      case Some(apiKey) =>
        val request = OpenRouterRequest(
          model = model,
          messages = List(
            OpenRouterMessage("system", Left(systemPrompt)),
            OpenRouterMessage("user", Left(userContent))
          )
        )

        callOpenRouterAPI(request, apiKey)
    }
  }

  private def callOpenRouterAPI(request: OpenRouterRequest, apiKey: String): String = {
    val requestBody = request.asJson.noSpaces
    
    def attemptRequest(attempt: Int): String = {
      Try {
        val response = requests.post(
          "https://openrouter.ai/api/v1/chat/completions",
          data = requestBody,
          headers = Map(
            "Authorization" -> s"Bearer $apiKey",
            "Content-Type" -> "application/json",
            "HTTP-Referer" -> "http://localhost:8080",
            "X-Title" -> "Tunic Pay Document Processor"
          ),
          readTimeout = 45000,
          connectTimeout = 10000
        )
        
        if (response.statusCode == 200) {
          response.text()
        } else {
          throw new RuntimeException(s"HTTP ${response.statusCode}: ${response.text()}")
        }
      } match {
        case Success(responseBody) =>
          // Robust handling: check for error payloads; retry on transient provider errors
          Try(extractTextOrRaise(responseBody)) match {
            case Success(text) => text
            case Failure(err) =>
              if (attempt < 3 && shouldRetryBodyError(err)) {
                logger.warn(s"Body parse/provider error (attempt $attempt), retrying in 1 second: ${err.getMessage}")
                Thread.sleep(1000)
                attemptRequest(attempt + 1)
              } else {
                throw err
              }
          }
        case Failure(error) =>
          if (attempt < 3 && shouldRetry(error)) {
            logger.warn(s"Request failed (attempt $attempt), retrying in 1 second: ${error.getMessage}")
            Thread.sleep(1000)
            attemptRequest(attempt + 1)
          } else {
            throw error
          }
      }
    }
    
    attemptRequest(1)
  }

  // Extract assistant text or raise a helpful error if OpenRouter returned an error payload
  private def extractTextOrRaise(raw: String): String = {
    parse(raw) match {
      case Left(_) =>
        throw new RuntimeException(s"Unexpected response from OpenRouter: ${raw.take(200)}")
      case Right(json) =>
        val cur = json.hcursor
        // If error present, surface it
        cur.downField("error").focus match {
          case Some(err) =>
            val code = err.hcursor.get[String]("code").toOption.getOrElse("unknown_error")
            val msg  = err.hcursor.get[String]("message").toOption.getOrElse("Unknown error")
            throw new RuntimeException(s"OpenRouter error ($code): $msg")
          case None =>
            // Try normal choices shape
            cur.downField("choices").as[List[Json]] match {
              case Right(choices) if choices.nonEmpty =>
                val contentCur = choices.head.hcursor.downField("message").downField("content")
                contentCur.as[String].getOrElse(throw new RuntimeException("OpenRouter returned no text content"))
              case _ =>
                throw new RuntimeException("Failed to parse OpenRouter response: missing choices")
            }
        }
    }
  }

  private def shouldRetryBodyError(error: Throwable): Boolean = {
    val msg = Option(error.getMessage).getOrElse("").toLowerCase
    msg.contains("provider returned error") ||
    msg.contains("temporarily unavailable") ||
    msg.contains("rate limit") ||
    msg.contains("overloaded")
  }

  // FIXME: Look into why this isn't using error codes directly
  private def shouldRetry(error: Throwable): Boolean = {
    error.getMessage match {
      case msg if msg.contains("400") => 
        logger.debug(s"400 Bad Request error (not retrying): $msg")
        false
      case msg if msg.contains("429") => true  // Rate limit
      case msg if msg.contains("408") => true  // Timeout
      case msg if msg.contains("5") => true    // 5xx server errors // FIXME: This could be matching on all sorts that is wrong.
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

// TODO: Move data model related case classes to a separate file
final case class OpenRouterRequest(
  model: String,
  messages: List[OpenRouterMessage]
)
object OpenRouterRequest {
  implicit val openRouterRequestEncoder: Encoder[OpenRouterRequest] = deriveEncoder[OpenRouterRequest]
}

final case class OpenRouterMessage(
  role: String,
  content: Either[String, List[MessageContent]]
)
object OpenRouterMessage {
  implicit val openRouterMessageEncoder: Encoder[OpenRouterMessage] = Encoder.instance { msg =>
    Json.obj(
      "role" -> Json.fromString(msg.role),
      "content" -> (msg.content match {
        case Left(text) => Json.fromString(text)
        case Right(contents) => contents.asJson
      })
    )
  }
  
  implicit val openRouterMessageDecoder: Decoder[OpenRouterMessage] = Decoder.instance { cursor =>
    for {
      role <- cursor.get[String]("role")
      content <- cursor.get[String]("content").map(Left(_)).orElse(
        cursor.get[List[MessageContent]]("content").map(Right(_))
      )
    } yield OpenRouterMessage(role, content)
  }
}

final case class MessageContent(
  `type`: String,
  text: Option[String] = None,
  image_url: Option[ImageUrl] = None
)
object MessageContent {
  implicit val messageContentEncoder: Encoder[MessageContent] = Encoder.instance { content =>
    val fields = List("type" -> Json.fromString(content.`type`)) ++ 
                 content.text.map(t => "text" -> Json.fromString(t)).toList ++
                 content.image_url.map(u => "image_url" -> u.asJson).toList
    Json.obj(fields: _*)
  }
  
  implicit val messageContentDecoder: Decoder[MessageContent] = deriveDecoder[MessageContent]
}

final case class ImageUrl(
  url: String,
  detail: Option[String] = None
)
object ImageUrl {
  implicit val imageUrlEncoder: Encoder[ImageUrl] = Encoder.instance { imageUrl =>
    val fields = List("url" -> Json.fromString(imageUrl.url)) ++ 
                 imageUrl.detail.map(d => "detail" -> Json.fromString(d)).toList
    Json.obj(fields: _*)
  }
  
  implicit val imageUrlDecoder: Decoder[ImageUrl] = deriveDecoder[ImageUrl]
}

final case class OpenRouterResponse(
  choices: List[OpenRouterChoice]
)
object OpenRouterResponse {
  implicit val openRouterResponseDecoder: Decoder[OpenRouterResponse] = deriveDecoder[OpenRouterResponse]
}

final case class OpenRouterChoice(
  message: OpenRouterMessage
)
object OpenRouterChoice {
  implicit val openRouterChoiceDecoder: Decoder[OpenRouterChoice] = deriveDecoder[OpenRouterChoice]
}

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