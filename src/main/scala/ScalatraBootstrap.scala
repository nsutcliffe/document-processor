package app

import org.scalatra.LifeCycle
import javax.servlet.ServletContext
import routes.FileRoutes
import services.{DatabaseService, FileService, LlmService, ExtractionService}
import config.AppConfig

class ScalatraBootstrap extends LifeCycle {
  override def init(context: ServletContext): Unit = {
    println("=== ScalatraBootstrap.init() CALLED ===")
    println(s"=== Context path: ${context.getContextPath} ===")
    
    try {
      // Initialize services
      println("=== Initializing services ===")
      val config = AppConfig.default
      val fileService = new FileService
      val llmService = new LlmService(config)
      val dbService = DatabaseService.instance
      val extractionService = new ExtractionService(llmService, dbService)
      println("=== Services initialized ===")

      // Mount the file routes at /api
      val fileRoutes = new FileRoutes(fileService, llmService, dbService, extractionService)
      context.mount(fileRoutes, "/api")
      println("=== MOUNTED FileRoutes at /api ===")
      println("=== Available routes should be: /api/files/upload, /api/files/:id, /api/files/:id/download ===")
    } catch {
      case e: Exception =>
        println(s"=== ERROR in ScalatraBootstrap: ${e.getMessage} ===")
        e.printStackTrace()
        throw e
    }
  }
}