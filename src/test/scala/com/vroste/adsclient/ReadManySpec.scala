package com.vroste.adsclient

import java.time.{LocalDate, LocalDateTime, LocalTime}

import com.vroste.adsclient.codec.AdsCodecs._
import monix.execution.Scheduler.Implicits.global
import monix.reactive.Consumer
import org.scalatest.{AsyncFlatSpec, MustMatchers}
import scodec.Codec

class ReadManySpec extends AsyncFlatSpec with MustMatchers {
  def consumerToSeq[T]: Consumer[T, Seq[T]] = Consumer.foldLeft(Seq.empty[T])(_ :+ _)

  val settings = AdsConnectionSettings(AmsNetId.fromString("10.211.55.3.1.1"), 851, AmsNetId.fromString("10.211.55.3.1.2"), 39205, "10.211.55.3")


  it must "read many variables at once" in {
    val result = for {
      client <- AdsClient.connect(settings)
      var1 <- client
        .readMany("MAIN.var1", int)
        .and("MAIN.var2", string)
        .and("MAIN.var4", int)
        .and("MAIN.var5", bool)
        .read
      var1r = var1.to
      _ = var1r
      var2 <- client.read("MAIN.var2", string)
      var4 <- client.read("MAIN.var4", int)
      _ = println(s"Var1: ${var1}, Var2: ${var2}, Var4: ${var4}")
    } yield succeed
    result.runAsync
  }
}
