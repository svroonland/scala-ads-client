package com.vroste.adsclient

import AdsCodecs._
import monix.reactive.Consumer
import org.scalatest.{AsyncFlatSpec, MustMatchers}
import shapeless._
import TestUtil._
import scodec.Codec

class ReadManySpec extends AsyncFlatSpec with MustMatchers {
  def consumerToSeq[T]: Consumer[T, Seq[T]] = Consumer.foldLeft(Seq.empty[T])(_ :+ _)

  val settings = AdsConnectionSettings(AmsNetId.fromString("10.211.55.3.1.1"), 851, AmsNetId.fromString("10.211.55.3.1.2"), 39205, "10.211.55.3")

  it must "read many variables at once" in {
    withClient { client =>
      val variableList = VariableList("MAIN.var1", int) +
        ("MAIN.var2", string) +
        ("MAIN.var4", int) + ("MAIN.var5", Codec[MyStruct])
      for {
        var1r <- client.read(variableList)
        data = Generic[MyManyData].from(var1r)
        _ = println(s"Var1: ${data.var1}, Var2: ${data.var2}, Var4: ${data.var4}, Var5: ${data.var5}")
      } yield succeed
    }
  }
}
