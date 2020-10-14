lazy val root = (project in file(".")).settings(
  inThisBuild(
    List(
      organization := "nl.vroste",
      scalaVersion := "2.13.3",
      version := "0.1.0-SNAPSHOT",
      name := "scala-ads-client"
    )
  ),
  name := "Scala ADS client",
  libraryDependencies ++= Seq(
    "dev.zio"      %% "zio"          % "1.0.3",
    "dev.zio"      %% "zio-streams"  % "1.0.3",
    "dev.zio"      %% "zio-test"     % "1.0.3" % "test",
    "dev.zio"      %% "zio-test-sbt" % "1.0.3" % "test",
    "dev.zio"      %% "zio-nio"      % "1.0.0-RC10",
    "com.beachape" %% "enumeratum"   % "1.5.13",
    "org.scodec"   %% "scodec-bits"  % "1.1.12",
    "org.scodec"   %% "scodec-core"  % "1.11.4"
  ),
  parallelExecution in Test := false
)

testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")

addCommandAlias("fmt", "all scalafmtSbt scalafmt test:scalafmt")
addCommandAlias("check", "all scalafmtSbtCheck scalafmtCheck test:scalafmtCheck")
