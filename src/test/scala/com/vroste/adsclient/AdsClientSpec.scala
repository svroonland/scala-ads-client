package com.vroste.adsclient

import java.time.{LocalDate, LocalDateTime, LocalTime}

import com.vroste.adsclient.TestUtil._
import monix.eval.Task
import monix.reactive.Consumer
import org.scalatest.{AsyncFlatSpec, MustMatchers}
import scodec.Codec

class AdsClientSpec extends AsyncFlatSpec with MustMatchers {
  def consumerToSeq[T]: Consumer[T, Seq[T]] = Consumer.foldLeft(Seq.empty[T])(_ :+ _)

  "ADSClient" must "connect and close to a PLC" in {
    withClient { client =>
      Task.pure(succeed)
    }
  }

  it must "observe a changing variable" in {
    withClient { client =>
      val var1 = client.notificationsFor("MAIN.var4", int)
      for {
        result <- var1.take(3).map(_.value).consumeWith(consumerToSeq).executeAsync
        _ = println(s"Result: ${result.mkString(", ")}")
      } yield result.size mustBe 3
    }
  }

  it must "read a variable" in {
    withClient { client =>
      for {
        var1 <- client.read("MAIN.var1", int)
        var2 <- client.read("MAIN.var2", string)
        var4 <- client.read("MAIN.var4", int)
        _ = println(s"Var1: ${var1}, Var2: ${var2}, Var4: ${var4}")
      } yield succeed
    }
  }

  it must "read a date" in {
    withClient { client =>
      for {
        result <- client.read("MAIN.var7", date)
      } yield result mustBe LocalDate.of(2016, 3, 8)
    }
  }

  it must "read a date and time" in {
    withClient { client =>
      for {
        _ <- Task.eval(println("Running read a datetime"))
        result <- client.read("MAIN.var9", dateAndTime)
      } yield result mustBe LocalDateTime.of(2016, 3, 8, 12, 13, 14)
    }
  }

  it must "read a time of day" in {
    withClient { client =>
      for {
        result <- client.read("MAIN.var10", timeOfDay)
      } yield result mustBe LocalTime.of(15, 36, 30, 123000000)
    }
  }

  it must "read an array variable" in {
    withClient { client =>
      val codec = array(10, int)
      for {
        array <- client.read("MAIN.var8", codec)
      } yield array mustBe List(1, 3, 2, 4, 3, 5, 4, 6, 5, 7)
    }
  }

  it must "read a STRUCT as case class" in {
    withClient { client =>
      for {
        struct <- client.read("MAIN.var5", Codec[MyStruct])
      } yield succeed
    }
  }

  it must "give error when reading a variable that does not exist" in {
    withClient { client =>
      val result = for {
        _ <- client.read("MAIN.varNotExist", int)
      } yield fail("expected ADSException")

      result.onErrorRecover { case AdsClientException(_) => succeed }
    }
  }

  it must "give error when registering notifications for a variable that does not exist" in {
    withClient { client =>
      val result = for {
        _ <- client.notificationsFor("MAIN.varNotExist", int).firstOptionL
      } yield fail("expected ADSException")
      result.onErrorRecover { case AdsClientException(_) => succeed }
    }
  }

  it must "give errors when reading a variable of the wrong type" in {
    withClient { client =>
      val result = for {
        _ <- client.read("MAIN.var1", lreal)
      } yield fail
      result.onErrorRecover { case AdsClientException(_) => succeed }
    }
  }

  // Run manually
  it must "read ADS state changes" in {
    withClient { client =>
      val statusChanges = client.stateChanges

      import scala.concurrent.duration._
      for {
        _ <- statusChanges.takeByTimespan(30.seconds).doOnNext(println).completedL
      } yield succeed
    }
  }
}


