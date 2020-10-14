package nl.vroste.adsclient

import zio.clock.Clock
import zio.console.putStrLn
import zio.stream.Sink
import zio.test._
import zio.test.Assertion._

object NotificationsSpec extends DefaultRunnableSpec {
  import AdsCodecs._
  override def spec =
    suite("Notifications")(
      testM("observe a changing variable") {
        val var1 = AdsClient.notificationsFor("MAIN.var4", int)
        for {
          result <- var1.tap(r => putStrLn(s"Got ${r.toString}")).take(3).map(_.value).run(Sink.collectAll[Short])
          _       = println(s"Result: ${result.mkString(", ")}")
        } yield assert(result)(hasSize(equalTo(3)))
      },
      testM("give error when registering notifications for a variable that does not exist") {
        for {
          result <- AdsClient.notificationsFor("MAIN.varNotExist", int).runDrain.run
        } yield assert(result)(fails(isSubtype[AdsClientError](anything)))
      },
      testM("give errors when reading a variable of the wrong type") {
        for {
          result <- AdsClient.notificationsFor("MAIN.var1", lreal).runDrain.run
        } yield assert(result)(fails(isSubtype[AdsClientError](anything)))
      }
    ).provideCustomLayerShared(Clock.live >+> AdsClient.connect(TestUtil.settings).toLayer.orDie) // @@ nonFlaky
}
