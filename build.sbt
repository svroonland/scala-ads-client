import Dependencies._

lazy val root = (project in file(".")).
  settings(
    inThisBuild(List(
      organization := "nl.vroste",
      scalaVersion := "2.12.6",
      version := "0.1.0-SNAPSHOT",
      name :="scala-ads-client"
    )),
    name := "Scala ADS client",
    libraryDependencies ++= Seq(
      scalaTest % Test,
      "dev.zio" %% "zio" % "1.0.0-RC12-1",
      "dev.zio" %% "zio-streams" % "1.0.0-RC12-1",
      "dev.zio" %% "zio-nio" % "0.1.3-M6",
      "com.beachape" %% "enumeratum" % "1.5.13",
      "org.scodec" %% "scodec-bits" % "1.1.12",
      "org.scodec" %% "scodec-core" % "1.11.4"
    )
  )
