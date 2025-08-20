ThisBuild / scalaVersion := "2.13.16"

lazy val root = (project in file(".")).settings(
  name := "tunic-pay-backend",
  libraryDependencies ++= Seq(
    // http4s
    "org.http4s" %% "http4s-dsl" % "0.23.24",
    "org.http4s" %% "http4s-ember-server" % "0.23.24",
    "org.http4s" %% "http4s-circe" % "0.23.24",
    // JSON
    "io.circe" %% "circe-core" % "0.14.6",
    "io.circe" %% "circe-generic" % "0.14.6",
    "io.circe" %% "circe-parser" % "0.14.6",
    // DB
    "org.scalikejdbc" %% "scalikejdbc" % "4.0.0",
    "com.h2database" % "h2" % "2.2.224",
    // File/content utilities
    "org.apache.pdfbox" % "pdfbox" % "2.0.29",
    "org.apache.tika" % "tika-core" % "2.9.1",
    // HTTP client for OpenRouter
    "org.http4s" %% "http4s-ember-client" % "0.23.24",
    // Logging
    "org.typelevel" %% "log4cats-slf4j" % "2.6.0",
    "ch.qos.logback" % "logback-classic" % "1.5.6",
    // Testing
    "org.scalatest" %% "scalatest" % "3.2.17" % Test,
    "org.scalatestplus" %% "mockito-4-6" % "3.2.15.0" % Test,
    "org.typelevel" %% "cats-effect-testing-scalatest" % "1.5.0" % Test
  )
)