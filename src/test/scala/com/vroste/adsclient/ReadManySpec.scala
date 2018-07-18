package com.vroste.adsclient

import AdsCodecs._
import monix.reactive.Consumer
import org.scalatest.{AsyncFlatSpec, MustMatchers}
import shapeless._
import TestUtil._
import com.vroste.adsclient.internal.AdsClientException
import scodec.Codec

class ReadManySpec extends AsyncFlatSpec with MustMatchers {
  def consumerToSeq[T]: Consumer[T, Seq[T]] = Consumer.foldLeft(Seq.empty[T])(_ :+ _)

  val settings = AdsConnectionSettings(AmsNetId.fromString("10.211.55.3.1.1"), 851, AmsNetId.fromString("10.211.55.3.1.2"), 39205, "10.211.55.3")

  "The ADS client" must "read many variables at once" in {
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

  it must "write many variables at once" in {
    import shapeless.::
    withClient { client =>
      val variableList = VariableList("MAIN.var1", int) + ("MAIN.var4", int)
      //        ("MAIN.var4", int) + ("MAIN.var5", Codec[MyStruct])

      for {
        _ <- client.write(variableList, 8 :: 10 :: HNil) // "New Value" :: 3 :: MyStruct(1, true) :: HNil)
      } yield succeed
    }
  }

  it must "give an error when passing the wrong codec to one of the variables" in {
    withClient { client =>
      val variableList = VariableList("MAIN.var1", string) +
        ("MAIN.var2", string)
      val result = for {
        var1r <- client.read(variableList)
        (var1, var2) = var1r.tupled
        _ = println(s"Var1: ${var1}, Var2: ${var2}")
      } yield fail

      result.onErrorRecover { case AdsClientException(e) =>
        println(s"Got ADS exception: ${e}")
        succeed
      }
    }
  }

  it must "give an error when attemptign to read a variable that does not exist" in {
    withClient { client =>
      val variableList = VariableList("MAIN.var1", int) +
        ("MAIN.varNotExist", string)
      val result = for {
        var1r <- client.read(variableList)
        (var1, var2) = var1r.tupled
        _ = println(s"Var1: ${var1}, Var2: ${var2}")
      } yield fail

      result.onErrorRecover { case AdsClientException(e) =>
        println(s"Got ADS exception: ${e}")
        succeed
      }
    }
  }
}
