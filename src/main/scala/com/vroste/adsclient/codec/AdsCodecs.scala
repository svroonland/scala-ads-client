package com.vroste.adsclient.codec

import java.time._

import scodec.bits.BitVector
import scodec.{Attempt, Codec, DecodeResult, Err, SizeBound, codecs => scodecs}

import scala.concurrent.duration.{FiniteDuration, _}

/**
  * Codecs for PLC variable types
  */
trait AdsCodecs {
  val bool: Codec[Boolean] = scodecs.bool(8)
  val byte: Codec[Byte] = scodecs.byte
  val word: Codec[Int] = scodecs.uint16L
  val dword: Codec[Long] = scodecs.uint32L
  val sint: Codec[Int] = scodecs.int8L
  val usint: Codec[Int] = scodecs.uint8L
  val int: Codec[Int] = scodecs.int16L
  val uint: Codec[Int] = scodecs.uint16L
  val dint: Codec[Int] = scodecs.int32L
  val udint: Codec[Long] = scodecs.uint32L
  val real: Codec[Float] = scodecs.floatL
  val lreal: Codec[Double] = scodecs.doubleL

  val instant: Codec[Instant] = dword.xmap(Instant.ofEpochSecond, _.getEpochSecond)

  private val utc = ZoneOffset.UTC

  val date: Codec[LocalDate] =
    instant.xmap(_.atOffset(utc).toLocalDate, Instant.from)

  val dateAndTime: Codec[LocalDateTime] = {
    instant.xmap(_.atOffset(utc).toLocalDateTime, _.atOffset(utc).toInstant)
  }

  val time: Codec[FiniteDuration] = dword.xmapc(_.milliseconds)(_.toMillis)

  private val milliToNano = 1000000
  val timeOfDay: Codec[LocalTime] = dword.xmapc(ms => LocalTime.ofNanoOfDay(ms * milliToNano))(_.toNanoOfDay / milliToNano)

  val maxDefaultStringLength = 80
  val string = stringN(maxDefaultStringLength)
  val wstring = scodecs.utf8

  def stringN(maxLength: Int): Codec[String] = {
    val baseCodec = scodecs.cstring.narrow[String](s =>
      if (s.length <= maxLength) {
        Attempt.successful(s)
      } else {
        Attempt.failure(Err(s"String ${s} is longer than maximum string length ${maxLength}"))
      },
      identity)

    new Codec[String] {
      override def decode(bits: BitVector): Attempt[DecodeResult[String]] = baseCodec.decode(bits)
      override def encode(value: String): Attempt[BitVector] = baseCodec.encode(value)
      override def sizeBound: SizeBound = SizeBound.atMost((maxLength + 1) * 8)
    }
  }

  def array[T](length: Int, elementCodec: Codec[T]): Codec[List[T]] = {
    val codec = scodecs.listOfN(scodecs.provide(length), elementCodec)

    new Codec[List[T]] {
      override def decode(bits: BitVector): Attempt[DecodeResult[List[T]]] = codec.decode(bits)
      override def encode(value: List[T]): Attempt[BitVector] = codec.encode(value)
      override def sizeBound: SizeBound = SizeBound.exact(length * elementCodec.sizeBound.lowerBound)
    }
  }
}

object AdsCodecs extends AdsCodecs

object CustomDataTypeReadableExample extends AdsCodecs {
  import scodec.codecs.StringEnrichedWithCodecContextSupport

  // How to define a codec for a custom data structure (case class)
  // It will be read and written as 3*4 bytes
  case class MyDummyObject(a: Int, b: Int, c: Boolean)

  val myDummyObjectCodec: Codec[MyDummyObject] = (("a" | int) :: ("b" | int) :: ("c" | bool)).as
}
