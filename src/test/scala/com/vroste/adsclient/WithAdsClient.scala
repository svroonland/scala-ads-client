package com.vroste.adsclient

import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Millis, Seconds, Span}

trait WithAdsClient extends BeforeAndAfterAll with ScalaFutures with TestUtil { this: BaseSpec =>
  implicit val defaultPatience =   PatienceConfig(timeout =  Span(5, Seconds), interval = Span(50, Millis))

  val client = AdsClient.connect(settings).runAsync.futureValue

  override def afterAll(): Unit = {
    client.close().runAsync.futureValue
  }
}
