package com.vroste.adsclient

import java.time.{LocalDate, LocalDateTime, LocalTime}

import com.vroste.adsclient.codec.AdsCodecs._
import monix.execution.Scheduler.Implicits.global
import monix.reactive.Consumer
import org.scalatest.{AsyncFlatSpec, MustMatchers}
import scodec.Codec

class AdsClientSpec extends AsyncFlatSpec with MustMatchers {
  def consumerToSeq[T]: Consumer[T, Seq[T]] = Consumer.foldLeft(Seq.empty[T])(_ :+ _)

  val settings = AdsConnectionSettings(AmsNetId.fromString("10.211.55.3.1.1"), 851, AmsNetId.fromString("10.211.55.3.1.2"), 39205, "10.211.55.3")

  "ADSClient" must "connect and close to a PLC" in {
    val result = for {
      client <- AdsClient.connect(settings)
      _ <- client.close()
    } yield succeed
    result.runAsync
  }

  it must "observe a changing variable" in {
    val result = for {
      client <- AdsClient.connect(settings)
      var1 = client.notificationsFor("MAIN.var4", int)
      result <- var1.take(3).map(_.value).consumeWith(consumerToSeq).executeAsync
      _ = println(s"Result: ${result.mkString(", ")}")
      _ <- client.close()
    } yield result.size mustBe 3
    result.runAsync
  }

  it must "read a variable" in {
    val result = for {
      client <- AdsClient.connect(settings)
      var1 <- client.read("MAIN.var1", int)
      var2 <- client.read("MAIN.var2", string)
      var4 <- client.read("MAIN.var4", int)
      _ = println(s"Var1: ${var1}, Var2: ${var2}, Var4: ${var4}")
    } yield succeed
    result.runAsync
  }

  it must "read a date" in {
    val result = for {
      client <- AdsClient.connect(settings)
      result <- client.read("MAIN.var7", date)
    } yield result mustBe LocalDate.of(2016, 3, 8)
    result.runAsync
  }

  it must "read a date and time" in {
    val result = for {
      client <- AdsClient.connect(settings)
      result <- client.read("MAIN.var9", dateAndTime)
    } yield result mustBe LocalDateTime.of(2016, 3, 8, 12, 13, 14)
    result.runAsync
  }

  it must "read a time of day" in {
    val result = for {
      client <- AdsClient.connect(settings)
      result <- client.read("MAIN.var10", timeOfDay)
    } yield result mustBe LocalTime.of(15, 36, 30, 123000000)
    result.runAsync
  }

  it must "read an array variable" in {
    val result = for {
      client <- AdsClient.connect(settings)
      codec = array(10, int)
      array <- client.read("MAIN.var8", codec)
    } yield array mustBe List(1,3,2,4,3,5,4,6,5,7)
    result.runAsync
  }

  val myStructCodec: Codec[MyStruct] = (int :: bool).as

  it must "read a STRUCT as case class" in {
    val result = for {
      client <- AdsClient.connect(settings)
      struct <- client.read("MAIN.var5", myStructCodec)
    } yield succeed
    result.runAsync
  }

  it must "give error when reading a variable that does not exist" in {
    val result = for {
      client <- AdsClient.connect(settings)
      _ <- client.read("MAIN.var9", int)
    } yield fail
    result.onErrorRecover { case AdsClientException(_) => succeed }.runAsync
  }

  it must "give error when registering notifications for a variable that does not exist" in {
    val result = for {
      client <- AdsClient.connect(settings)
      _ <- client.notificationsFor("MAIN.var9", int).firstOptionL
    } yield fail
    result.onErrorRecover { case AdsClientException(_) => succeed }.runAsync
  }

  it must "give errors when reading a variable of the wrong type" in {
    val result = for {
      client <- AdsClient.connect(settings)
      _ <- client.read("MAIN.var1", lreal)
    } yield fail
    result.onErrorRecover { case AdsClientException(_) => succeed }.runAsync

  }
}

case class MyStruct(myInt: Int, myBool: Boolean)
