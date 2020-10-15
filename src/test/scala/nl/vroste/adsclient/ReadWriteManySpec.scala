package nl.vroste.adsclient

import scodec.Codec
import shapeless._
import zio.clock.Clock
import zio.test._
import zio.test.Assertion._
import zio.test.TestAspect.nonFlaky

object ReadWriteManySpec extends DefaultRunnableSpec {
  import AdsCodecs._

  case class MyManyData(var1: Short, var2: String, var4: Short, var5: MyStruct)

  val variableList =
    VariableList("MAIN.var1", int).+("MAIN.var2", string).+("MAIN.var4", int).+("MAIN.var5", Codec[MyStruct])

  val genMyData = Generic[MyManyData]

  override def spec =
    suite("ADS client")(
      testM("read many variables at once") {
        for {
          var1r <- AdsClient.read(variableList)
          data   = genMyData.from(var1r)
          _      = println(s"Var1: ${data.var1}, Var2: ${data.var2}, Var4: ${data.var4}, Var5: ${data.var5}")
        } yield assertCompletes
      },
      testM("write many variables at once") {
        for {
          _ <-
            AdsClient.write(variableList, 8.toShort :: "New value !! Yes" :: 10.toShort :: MyStruct(103, true) :: HNil)
        } yield assertCompletes
      },
      testM("give an error when attemptign to read a variable that does not exist") {
        val variableList = VariableList("MAIN.var1", int).+("MAIN.varNotExist", string)
        for {
          result <- AdsClient.read(variableList).run
        } yield assert(result)(fails(isSubtype[AdsClientError](anything))) // TODO could be more specific
      }
    ).provideCustomLayerShared(Clock.live >+> AdsClient.connect(TestUtil.settings).toLayer.orDie) @@ nonFlaky
}
