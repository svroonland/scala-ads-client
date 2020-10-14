package nl.vroste.adsclient.manual

import nl.vroste.adsclient.BaseSpec
import nl.vroste.adsclient._
import scala.concurrent.duration._

class ConnectionLossSpec extends BaseSpec {
  "The ADS client" must "respond to connection loss " in {
    val changes = client.notificationsFor("MAIN.var1", int)

    changes
      .takeByTimespan(30.seconds)
      .foreach(println)
      .map(_ => succeed)
  }
}
