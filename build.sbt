ThisBuild / scalaVersion := "2.13.16"

lazy val root = (project in file(".")).settings(
  name := "tunic-pay-backend",
  libraryDependencies ++= Seq(
    // Web framework - Scalatra
    "org.scalatra" %% "scalatra" % "2.8.4",
    "org.scalatra" %% "scalatra-json" % "2.8.4",
    "org.json4s" %% "json4s-jackson" % "4.0.7",
    // Jetty (javax servlet API)
    "org.eclipse.jetty" % "jetty-server" % "9.4.53.v20231009",
    "org.eclipse.jetty" % "jetty-servlet" % "9.4.53.v20231009",
    "org.eclipse.jetty" % "jetty-webapp" % "9.4.53.v20231009",
    // Align servlet API with Jetty 9 (javax)
    "javax.servlet" % "javax.servlet-api" % "3.1.0",
    
    // HTTP client - requests-scala
    "com.lihaoyi" %% "requests" % "0.8.0",
    
    // JSON - keep Circe for consistency
    "io.circe" %% "circe-core" % "0.14.6",
    "io.circe" %% "circe-generic" % "0.14.6",
    "io.circe" %% "circe-parser" % "0.14.6",
    
    // DB - keep as is
    "org.scalikejdbc" %% "scalikejdbc" % "4.0.0",
    "com.h2database" % "h2" % "2.2.224",
    
    // File/content utilities - keep as is
    "org.apache.pdfbox" % "pdfbox" % "2.0.29",
    "org.apache.tika" % "tika-core" % "2.9.1",
    
    // Logging - simplified
    "ch.qos.logback" % "logback-classic" % "1.5.6",
    "org.slf4j" % "slf4j-api" % "2.0.9",
    
    // Testing - simplified
    "org.scalatest" %% "scalatest" % "3.2.17" % Test,
    "org.scalatestplus" %% "mockito-4-6" % "3.2.15.0" % Test
  )
)