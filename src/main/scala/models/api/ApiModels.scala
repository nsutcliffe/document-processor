package models.api

import io.circe._
import io.circe.generic.semiauto._

final case class EntityDto(`type`: String, value: String, confidence: Option[Double])
final case class TableDto(table_name: String, headers: List[String], rows: List[List[String]])

final case class UploadResponse(
  fileId: String,
  filename: String,
  fileSize: Long,
  fileType: String,
  category: String,
  confidenceScore: Double,
  entities: List[EntityDto],
  dates: List[String],
  tables: List[TableDto],
  downloadUrl: String
)

object ApiModels {
  implicit val entityEncoder: Encoder[EntityDto] = deriveEncoder
  implicit val tableEncoder: Encoder[TableDto] = deriveEncoder
  implicit val uploadRespEncoder: Encoder[UploadResponse] = deriveEncoder
}




