package config

final case class AppConfig(
  host: String = "0.0.0.0",
  port: Int = 8080,
  openRouterApiKey: Option[String] = sys.env.get("OPENROUTER_API_KEY")
)

object AppConfig {
  val default: AppConfig = AppConfig()
}




