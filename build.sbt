import Dependencies._

lazy val root = (project in file(".")).
  settings(
    inThisBuild(List(
      organization := "com.vroste",
      scalaVersion := "2.12.6",
      version := "0.1.0-SNAPSHOT"
    )),
    name := "Scala ADS client",
    libraryDependencies ++= Seq(
      scalaTest % Test,
      "io.monix" %% "monix-nio" % "0.0.3",
      "io.monix" %% "monix" % "3.0.0-RC1",
      "com.beachape" %% "enumeratum" % "1.5.13",
      "org.scodec" %% "scodec-bits" % "1.1.5",
      "org.scodec" %% "scodec-core" % "1.10.3"

    )
  )
