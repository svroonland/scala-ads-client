package com.vroste.adsclient

import monix.eval.Task

import scala.concurrent.Future
import scala.concurrent.duration._

import monix.execution.Scheduler.Implicits.global

object TestUtil {
  val settings = AdsConnectionSettings(AmsNetId.fromString("10.211.55.3.1.1"), 851, AmsNetId.fromString("10.211.55.3.1.2"), 39205, "10.211.55.3")

  def withClient[T](f: AdsClient => Task[T]): Future[T] =
    AdsClient.connect(settings).bracket(f)(_.close())
      .delayResult(500.millis).runAsync
}
