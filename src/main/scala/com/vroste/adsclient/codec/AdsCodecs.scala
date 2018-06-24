package com.vroste.adsclient.codec

import scodec.bits.BitVector
import scodec.{Attempt, Codec, DecodeResult, Err, SizeBound, codecs => scodecs}

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

  val maxDefaultStringLength = 80
  val string = stringN(maxDefaultStringLength)

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

  // TODO TIME, TIME_OF_DAY, DATE, DATE_AND_TIME, ENUMcomp
}

object AdsCodecs extends AdsCodecs

object CustomDataTypeReadableExample extends AdsCodecs {
  import scodec.codecs.StringEnrichedWithCodecContextSupport

  // How to define a codec for a custom data structure (case class)
  // It will be read and written as 3*4 bytes
  case class MyDummyObject(a: Int, b: Int, c: Boolean)

  val myDummyObjectCodec: Codec[MyDummyObject] = (("a" | int) :: ("b" | int) :: ("c" | bool)).as
}
