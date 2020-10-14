package nl.vroste.adsclient

import scodec.Codec
import shapeless._

class ReadWriteManySpec extends BaseSpec {

  case class MyManyData(var1: Short, var2: String, var4: Short, var5: MyStruct)

  val variableList = VariableList("MAIN.var1", int) +
    ("MAIN.var2", string) +
    ("MAIN.var4", int) + ("MAIN.var5", Codec[MyStruct])

  val genMyData = Generic[MyManyData]

  "The ADS client" must "read many variables at once" in {
    val result = for {
      var1r <- client.read(variableList)
      data   = genMyData.from(var1r)
      _      = println(s"Var1: ${data.var1}, Var2: ${data.var2}, Var4: ${data.var4}, Var5: ${data.var5}")
    } yield succeed

    result
  }

  it must "write many variables at once" in {
    val result = for {
      _ <- client.write(variableList, 8.toShort :: "New value !! Yes" :: 10.toShort :: MyStruct(103, true) :: HNil)
    } yield succeed

    result
  }

  it must "give an error when attemptign to read a variable that does not exist" in {
    val variableList = VariableList("MAIN.var1", int) +
      ("MAIN.varNotExist", string)
    val result       = for {
      var1r       <- client.read(variableList)
      (var1, var2) = var1r.tupled
      _            = println(s"Var1: ${var1}, Var2: ${var2}")
    } yield fail

    result.onErrorRecover {
      case AdsClientException(e) =>
        println(s"Got ADS exception: ${e}")
        succeed
    }
  }
}
