import Dependencies._

lazy val root = (project in file(".")).settings(
  inThisBuild(
    List(
      organization := "nl.vroste",
      scalaVersion := "2.12.13",
      version := "0.1.0-SNAPSHOT",
      name := "scala-ads-client"
    )
  ),
  name := "Scala ADS client",
  libraryDependencies ++= Seq(
    "io.monix"        %% "monix-nio"   % "0.0.9",
    "io.monix"        %% "monix"       % "3.4.0",
    "com.beachape"    %% "enumeratum"  % "1.6.1",
    "org.scodec"      %% "scodec-bits" % "1.1.27",
    "org.scodec"      %% "scodec-core" % "1.11.7"
  ) ++ scalaTest.map(_ % Test)
)

addCommandAlias("fmt", "all scalafmtSbt scalafmt test:scalafmt")
addCommandAlias("check", "all scalafmtSbtCheck scalafmtCheck test:scalafmtCheck")
