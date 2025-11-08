package app

import org.eclipse.jetty.server.Server
import org.eclipse.jetty.servlet.{DefaultServlet, ServletContextHandler, ServletHolder}
import services.{DatabaseService, FileService, LlmService, ExtractionService}
import routes.FileRoutes
import config.AppConfig
import org.slf4j.LoggerFactory
import javax.servlet.http.{HttpServlet, HttpServletRequest, HttpServletResponse}

// Wrapper servlet that delegates to FileRoutes
class FileRoutesWrapper(fileRoutes: FileRoutes) extends HttpServlet {
  override def service(req: HttpServletRequest, resp: HttpServletResponse): Unit = {
    fileRoutes.service(req, resp)
  }
}

object Main {
  private val logger = LoggerFactory.getLogger(getClass)
  
  def main(args: Array[String]): Unit = {
    logger.info("🚀 Starting Tunic Pay Document Processor Backend...")
    
    // Validate environment
    validateEnvironment()
    
    // Initialize database
    logger.info("📊 Initializing database...")
    DatabaseService.instance.init()
    logger.info("📊 Database initialized successfully")
    
    // Start server
    logger.info("🌐 Starting HTTP server on http://localhost:8080")
    val server = createServer()
    
    try {
      server.start()
      logger.info("✅ Server started successfully!")
      logger.info("🌐 Backend available at: http://localhost:8080")
      logger.info("⚠️  Keep this window open while using the application")
      server.join()
    } catch {
      case e: Exception =>
        logger.error("❌ Failed to start server", e)
        sys.exit(1)
    }
  }
  
  private def validateEnvironment(): Unit = {
    val apiKey = sys.env.get("OPENROUTER_API_KEY")
    apiKey match {
      case None =>
        logger.error("")
        logger.error("❌ FATAL ERROR: OPENROUTER_API_KEY environment variable is not set!")
        logger.error("")
        logger.error("🔧 To fix this issue:")
        logger.error("   Windows PowerShell: $env:OPENROUTER_API_KEY = \"your-key-here\"")
        logger.error("   Windows CMD:        set OPENROUTER_API_KEY=your-key-here")
        logger.error("   Linux/Mac:          export OPENROUTER_API_KEY=your-key-here")
        logger.error("")
        logger.error("💡 Get your API key from: https://openrouter.ai/keys")
        logger.error("")
        throw new RuntimeException("Missing required OPENROUTER_API_KEY environment variable")
      case Some(key) if key.trim.isEmpty =>
        logger.error("")
        logger.error("❌ FATAL ERROR: OPENROUTER_API_KEY is empty!")
        logger.error("")
        throw new RuntimeException("OPENROUTER_API_KEY environment variable is empty")
      case Some(key) =>
        logger.info(s"✅ OpenRouter API Key loaded: ${key.take(20)}...")
    }
  }
  
  private def createServer(): Server = {
    val server = new Server(8080)
    val context = new ServletContextHandler(ServletContextHandler.SESSIONS)
    context.setContextPath("/")
    
    // Initialize services manually
    logger.info("🔧 Initializing services...")
    val config = AppConfig.default
    val fileService = new FileService
    val llmService = new LlmService(config)
    val dbService = DatabaseService.instance
    val extractionService = new ExtractionService(llmService, dbService)
    logger.info("✅ Services initialized")
    
    // Create FileRoutes and mount directly with Servlet 3.0 multipart support
    val fileRoutes = new FileRoutes(fileService, dbService, extractionService)
    val holder = new ServletHolder(fileRoutes)
    val tmpDir = System.getProperty("java.io.tmpdir")
    holder.getRegistration.setMultipartConfig(new javax.servlet.MultipartConfigElement(
      tmpDir, 50L * 1024 * 1024, 60L * 1024 * 1024, 1 * 1024 * 1024
    ))
    context.addServlet(holder, "/api/*")
    
    logger.info("✅ Mounted FileRoutes at /api/* with multipart support")
    
    // Add default servlet for static content
    context.addServlet(new ServletHolder(new DefaultServlet()), "/*")
    
    server.setHandler(context)
    logger.info("🔧 Server configured with Scalatra mounting")
    server
  }
}