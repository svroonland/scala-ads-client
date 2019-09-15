package nl.vroste.adsclient

import scodec.Codec
import shapeless._
import zio.ZIO

class ReadWriteManySpec extends BaseSpec {

  case class MyManyData(var1: Short, var2: String, var4: Short, var5: MyStruct)

  val variableList = VariableList("MAIN.var1", int) +
    ("MAIN.var2", string) +
    ("MAIN.var4", int) + ("MAIN.var5", Codec[MyStruct])

  val genMyData = Generic[MyManyData]

  "The ADS client" must "read many variables at once" in {
    clientM.use { client =>
      for {
        var1r <- client.read(variableList)
        data  = genMyData.from(var1r)
        _     = println(s"Var1: ${data.var1}, Var2: ${data.var2}, Var4: ${data.var4}, Var5: ${data.var5}")
      } yield succeed
    }
  }

  it must "write many variables at once" in {
    clientM.use { client =>
      for {
        _ <- client.write(variableList, 8.toShort :: "New value !! Yes" :: 10.toShort :: MyStruct(103, true) :: HNil)
      } yield succeed
    }

  }

  it must "give an error when attemptign to read a variable that does not exist" in {
    clientM.use { client =>
      val variableList = VariableList("MAIN.var1", int) +
        ("MAIN.varNotExist", string)
      val result = for {
        var1r        <- client.read(variableList)
        (var1, var2) = var1r.tupled
        _            = println(s"Var1: ${var1}, Var2: ${var2}")
      } yield fail

      result.catchSome {
        case AdsClientException(e) =>
          println(s"Got ADS exception: ${e}")
          ZIO.succeed(succeed)
      }
    }
  }
}
