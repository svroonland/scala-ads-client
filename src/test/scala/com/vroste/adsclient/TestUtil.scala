package com.vroste.adsclient

import monix.eval.Task

import scala.concurrent.Future
import scala.concurrent.duration._

import monix.execution.Scheduler.Implicits.global

object TestUtil {
  val settings = AdsConnectionSettings(amsNetIdTarget = AmsNetId.fromString("10.211.55.3.1.1"), amsPortTarget = 851, amsNetIdSource = AmsNetId.fromString("192.168.0.102.1.1"), amsPortSource = 39205, hostname = "192.168.0.103")

  def withClient[T](f: AdsClient => Task[T]): Future[T] =
    AdsClient.connect(settings).bracket(f)(_.close())
      .delayResult(500.millis).runAsync
}
