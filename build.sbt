import Dependencies._

lazy val root = (project in file(".")).settings(
  inThisBuild(
    List(
      organization := "nl.vroste",
      scalaVersion := "2.12.6",
      version := "0.1.0-SNAPSHOT",
      name := "scala-ads-client"
    )),
  name := "Scala ADS client",
  libraryDependencies ++= Seq(
    scalaTest      % Test,
    "io.monix"     %% "monix-nio" % "0.0.3",
    "io.monix"     %% "monix" % "3.0.0-RC2",
    "com.beachape" %% "enumeratum" % "1.5.13",
    "org.scodec"   %% "scodec-bits" % "1.1.5",
    "org.scodec"   %% "scodec-core" % "1.10.3"
  )
)

addCommandAlias("fmt", "all scalafmtSbt scalafmt test:scalafmt")
addCommandAlias("check", "all scalafmtSbtCheck scalafmtCheck test:scalafmtCheck")
