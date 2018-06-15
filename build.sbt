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
      "io.monix" %% "monix-nio" % "0.0.3"
    )
  )
