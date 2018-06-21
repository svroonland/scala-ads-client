package com.vroste.adsclient

import com.vroste.adsclient.codec.AdsCodecs._
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import monix.reactive.Consumer
import org.scalatest.{AsyncFlatSpec, MustMatchers}

class AdsClientSpec extends AsyncFlatSpec with MustMatchers {
  def consumerToSeq[T]: Consumer[T, Seq[T]] = Consumer.foldLeft(Seq.empty[T])(_ :+ _)

  "ADSClient" must "connect and close to a PLC" ignore {
    val settings = AdsConnectionSettings(AmsNetId.fromString("10.211.55.3.1.1"), 801, AmsNetId.fromString("10.211.55.3.10.10"), 123, "localhost")

    val result = for {
      client <- AdsClient.connect(settings)
      _ <- client.close()
    } yield succeed
    result.runAsync
  }

  import scala.concurrent.duration._

  it must "observe a changing variable" in {
    val settings = AdsConnectionSettings(AmsNetId.fromString("10.211.55.3.1.1"), 851, AmsNetId.fromString("10.211.55.3.1.2"), 39205, "10.211.55.3")

    val result = for {
      client <- AdsClient.connect(settings)
      var1 = client.notificationsFor("MAIN.var4", int)
      result <- var1.take(3).map(_.value).consumeWith(consumerToSeq).executeAsync
//      _ = println("Running the same thing again!")
//      result2 <- var1.take(10).map(_.value).consumeWith(consumerToSeq)
      _ = println(s"Result: ${result.mkString(", ")}")
//      _ <- Task.pure(3).delayExecution(3.seconds).executeAsync
      _ = println("Done with delay")
      _ <- client.close()
    } yield result.size mustBe 3
    result.runAsync
  }

  it must "read a variable" ignore {
    val settings = AdsConnectionSettings(AmsNetId.fromString("10.211.55.3.1.1"), 851, AmsNetId.fromString("10.211.55.3.1.2"), 39205, "10.211.55.3")

    val result = for {
      client <- AdsClient.connect(settings)
      var1 <- client.read("MAIN.var1", int)
      var2 <- client.read("MAIN.var2", string)
      var4 <- client.read("MAIN.var4", int)
//      _ = println(s"Var2: ${var2}")
      _ = println(s"Var1: ${var1}, Var2: ${var2}, Var4: ${var4}")
    } yield succeed
    result.runAsync
  }
}
