package nl.vroste.adsclient

import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Millis, Seconds, Span}
import zio.ZManaged
import zio.clock.Clock

trait WithAdsClient extends ScalaFutures with TestUtil with ZioSupport { this: BaseSpec =>
  implicit val defaultPatience =   PatienceConfig(timeout =  Span(5, Seconds), interval = Span(50, Millis))

  val clientM: ZManaged[Clock, Exception, AdsClient] = AdsClient.connect(settings)
}
