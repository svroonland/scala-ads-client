package nl.vroste.adsclient.manual

import nl.vroste.adsclient.BaseSpec
import nl.vroste.adsclient._
import zio.Schedule
import zio.stream.Sink
import zio.duration._

class ConnectionLossSpec extends BaseSpec {
  // TODO
//  "The ADS client" must "respond to connection loss " in {
//    clientM.use { client =>
//      val changes = client.notificationsFor("MAIN.var1", int)
//
//      changes
//          .
//        .aggregateWithin(Sink.collectAll[AdsNotification[Int]], Schedule.spaced(30.seconds))
//        .foreach(println)
//        .map(_ => succeed)
//    }
//  }
}
