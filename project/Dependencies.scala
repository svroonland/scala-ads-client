import sbt._

object Dependencies {
  val scalatestVersion = "3.2.2"
  lazy val scalaTest   = Seq(
    "org.scalatest" %% "scalatest"              % scalatestVersion,
    "org.scalatest" %% "scalatest-mustmatchers" % scalatestVersion
  )
}
