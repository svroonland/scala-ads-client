package nl.vroste.adsclient

import java.time.{ LocalDate, LocalDateTime, LocalTime }

import zio.{ Task, ZIO }
import scodec.Codec

case class MyStruct(myInt: Short, myBool: Boolean)

object MyStruct {
  implicit val codec: Codec[MyStruct] = (int :: bool).as
}

class ReadWriteSpec extends BaseSpec {
  it must "read an int" in {
    clientM.use { client =>
      val result = for {
        _ <- client.read("MAIN.var1", int)
      } yield succeed

      result
    }
  }

  it must "read a string" in {
    clientM.use { client =>
      val result = for {
        _ <- client.read("MAIN.var1", int)
      } yield succeed

      result
    }
  }

  it must "read a date" in {
    clientM.use { client =>
      val result = for {
        result <- client.read("MAIN.var7", date)
      } yield result mustBe LocalDate.of(2016, 3, 8)

      result
    }
  }

  it must "read a date and time" in {
    clientM.use { client =>
      val result = for {
        _      <- Task(println("Running read a datetime"))
        result <- client.read("MAIN.var9", dateAndTime)
      } yield result mustBe LocalDateTime.of(2016, 3, 8, 12, 13, 14)

      result
    }
  }

  it must "read a time of day" in {
    clientM.use { client =>
      val result = for {
        result <- client.read("MAIN.var10", timeOfDay)
      } yield result mustBe LocalTime.of(15, 36, 30, 123000000)

      result
    }
  }

  it must "read an array variable" in {
    clientM.use { client =>
      val codec = array(10, int)
      val result = for {
        array <- client.read("MAIN.var8", codec)
      } yield array mustBe List(1, 3, 2, 4, 3, 5, 4, 6, 5, 7)

      result
    }
  }

  it must "read a STRUCT as case class" in {
    clientM.use { client =>
      val result = for {
        struct <- client.read("MAIN.var5", Codec[MyStruct])
      } yield succeed

      result
    }
  }

  it must "give error when reading a variable that does not exist" in {
    clientM.use { client =>
      val result = for {
        _ <- client.read("MAIN.varNotExist", int)
      } yield fail("expected ADSException")

      result.catchSome { case AdsClientException(_) => ZIO.succeed(succeed) }
    }
  }

  it must "give errors when reading a variable of the wrong type" in {
    clientM.use { client =>
      val result = for {
        _ <- client.read("MAIN.var1", lreal)
      } yield fail

      result.catchSome { case DecodingError(e) => ZIO.succeed(succeed) }
    }
  }
}
