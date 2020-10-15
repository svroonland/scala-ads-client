import Dependencies._

val mainScala = "2.13.3"
val allScala  = Seq("2.12.12", mainScala)

lazy val root = (project in file(".")).settings(
  inThisBuild(
    List(
      organization := "nl.vroste",
      scalaVersion := mainScala,
      crossScalaVersions := allScala,
      version := "0.1.0-SNAPSHOT",
      name := "scala-ads-client"
    )
  ),
  name := "Scala ADS client",
  libraryDependencies ++= Seq(
    "io.monix"        %% "monix-nio"   % "0.0.9",
    "io.monix"        %% "monix"       % "3.2.2",
    "com.beachape"    %% "enumeratum"  % "1.5.15",
    "org.scodec"      %% "scodec-bits" % "1.1.20",
    "org.scodec"      %% "scodec-core" % "1.11.7"
  ) ++ scalaTest.map(_ % Test)
)

addCommandAlias("fmt", "all scalafmtSbt scalafmt test:scalafmt")
addCommandAlias("check", "all scalafmtSbtCheck scalafmtCheck test:scalafmtCheck")
