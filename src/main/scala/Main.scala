package app

import cats.effect._
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits._
import com.comcast.ip4s._
import routes.FileRoutes
import services.DatabaseService

object Main extends IOApp.Simple {
  
  private def validateEnvironment(): IO[Unit] = {
    val apiKey = sys.env.get("OPENROUTER_API_KEY")
    apiKey match {
      case None =>
        IO(println("")) *>
        IO(println("❌ FATAL ERROR: OPENROUTER_API_KEY environment variable is not set!")) *>
        IO(println("")) *>
        IO(println("🔧 To fix this issue:")) *>
        IO(println("   Windows PowerShell: $env:OPENROUTER_API_KEY = \"your-key-here\"")) *>
        IO(println("   Windows CMD:        set OPENROUTER_API_KEY=your-key-here")) *>
        IO(println("   Linux/Mac:          export OPENROUTER_API_KEY=your-key-here")) *>
        IO(println("")) *>
        IO(println("💡 Get your API key from: https://openrouter.ai/keys")) *>
        IO(println("")) *>
        IO.raiseError(new RuntimeException("Missing required OPENROUTER_API_KEY environment variable"))
      case Some(key) if key.trim.isEmpty =>
        IO(println("")) *>
        IO(println("❌ FATAL ERROR: OPENROUTER_API_KEY is empty!")) *>
        IO(println("")) *>
        IO.raiseError(new RuntimeException("OPENROUTER_API_KEY environment variable is empty"))
      case Some(key) =>
        IO(println(s"✅ OpenRouter API Key loaded: ${key.take(20)}...")) *>
        IO.unit
    }
  }

  private val httpApp: HttpApp[IO] = (
    new FileRoutes().routes
  ).orNotFound

  override def run: IO[Unit] = for {
    _ <- IO(println("🚀 Starting Tunic Pay Document Processor Backend..."))
    _ <- validateEnvironment()
    _ <- DatabaseService.instance.init()
    _ <- IO(println("📊 Database initialized successfully"))
    _ <- IO(println("🌐 Starting HTTP server on http://localhost:8080"))
    _ <- EmberServerBuilder.default[IO]
      .withHost(ipv4"0.0.0.0")
      .withPort(port"8080")
      .withHttpApp(httpApp)
      .build
      .useForever
  } yield ()
}


