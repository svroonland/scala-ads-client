package com.vroste.adsclient

import com.vroste.adsclient.codec.AdsCodecs._
import monix.execution.Scheduler.Implicits.global
import monix.reactive.Consumer
import org.scalatest.{AsyncFlatSpec, MustMatchers}
import shapeless._

class ReadManySpec extends AsyncFlatSpec with MustMatchers {
  def consumerToSeq[T]: Consumer[T, Seq[T]] = Consumer.foldLeft(Seq.empty[T])(_ :+ _)

  val settings = AdsConnectionSettings(AmsNetId.fromString("10.211.55.3.1.1"), 851, AmsNetId.fromString("10.211.55.3.1.2"), 39205, "10.211.55.3")

  it must "read many variables at once" in {
    val result = for {
      client <- AdsClient.connect(settings)
      variableList = VariableList("MAIN.var1", int) + ("MAIN.var2", string) + ("MAIN.var4", int) + ("MAIN.var5", bool)
      var1r <- client.read(variableList)
      data = Generic[MyManyData].from(var1r) // var1r..to[MyManyData] // (MyManyData.apply _).tupled.apply(var1r.tupled)
      _ = println(s"Var1: ${data.var1}, Var2: ${data.var2}, Var4: ${data.var4}")
    } yield succeed
    result.runAsync
  }
}

case class MyManyData(var1: Int, var2: String, var4: Int, var5: Boolean)
