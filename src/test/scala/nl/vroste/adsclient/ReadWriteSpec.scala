package nl.vroste.adsclient

import java.time.{ LocalDate, LocalDateTime, LocalTime }

import nl.vroste.adsclient.AdsCodecs._
import scodec.Codec
import zio.Task
import zio.clock.Clock
import zio.test.Assertion._
import zio.test._

case class MyStruct(myInt: Short, myBool: Boolean)

object MyStruct {
  implicit val codec: Codec[MyStruct] = (int :: bool).as
}

object ReadWriteSpec extends DefaultRunnableSpec {
  override def spec =
    suite("AdsClient")(
      testM("read an int") {
        for {
          _ <- AdsClient.read("MAIN.var1", int)
        } yield assertCompletes
      },
      testM("read a string") {
        for {
          _ <- AdsClient.read("MAIN.var1", int)
        } yield assertCompletes
      },
      testM("read a date") {
        for {
          result <- AdsClient.read("MAIN.var7", date)
        } yield assert(result)(equalTo(LocalDate.of(2016, 3, 8)))
      },
      testM("read a date and time") {
        for {
          _      <- Task(println("Running read a datetime"))
          result <- AdsClient.read("MAIN.var9", dateAndTime)
        } yield assert(result)(equalTo(LocalDateTime.of(2016, 3, 8, 12, 13, 14)))
      },
      testM("read a time of day") {
        for {
          result <- AdsClient.read("MAIN.var10", timeOfDay)
        } yield assert(result)(equalTo(LocalTime.of(15, 36, 30, 123000000)))
      },
      testM("read an array variable") {
        val codec = array(10, int)
        for {
          array <- AdsClient.read("MAIN.var8", codec)
        } yield assert(array)(equalTo(List[Short](1, 3, 2, 4, 3, 5, 4, 6, 5, 7)))
      },
      testM("read a STRUCT as case class") {
        for {
          _ <- AdsClient.read("MAIN.var5", Codec[MyStruct])
        } yield assertCompletes
      },
      testM("give error when reading a variable that does not exist") {
        for {
          result <- AdsClient.read("MAIN.varNotExist", int).flip
        } yield assert(result)(isSubtype[AdsErrorResponse](anything))
      },
      testM("give errors when reading a variable of the wrong type") {
        for {
          result <- AdsClient.read("MAIN.var1", lreal).flip
        } yield assert(result)(isSubtype[DecodingError](anything))
      }
    ).provideCustomLayerShared(Clock.live >+> AdsClient.connect(TestUtil.settings).toLayer.orDie)
}
