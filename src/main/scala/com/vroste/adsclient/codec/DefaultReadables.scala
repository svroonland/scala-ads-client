package com.vroste.adsclient.codec

import java.nio.ByteBuffer

object DefaultReadables {
  implicit val byteReadable: AdsReadable[Byte]     = withByteBuffer(1)(_.get())
  implicit val shortReadable: AdsReadable[Short]   = withByteBuffer(2)(_.getShort)
  implicit val charReadable: AdsReadable[Char]     = withByteBuffer(2)(_.getChar)
  implicit val intReadable: AdsReadable[Int]       = withByteBuffer(4)(_.getInt)
  implicit val boolReadable: AdsReadable[Boolean]  = withByteBuffer(1)(_.getInt == 1)
  implicit val floatReadable: AdsReadable[Float]   = withByteBuffer(4)(_.getFloat)
  implicit val doubleReadable: AdsReadable[Double] = withByteBuffer(8)(_.getDouble)
  implicit val stringReadable: AdsReadable[String] =
    withByteBuffer(81)(bb => new String(bb.array().takeWhile(_ != 0x00.toByte)))

  def byteArrayReadable(nrElements: Int): AdsReadable[Array[Byte]] = new AdsReadable[Array[Byte]] {
    override def fromBytes(bytes: Array[Byte]): Array[Byte] = bytes

    override def size: Int = nrElements
  }

  def seqReadable[T: AdsReadable](nrElements: Int): AdsReadable[Seq[T]] = new AdsReadable[Seq[T]] {
    val tReadable = implicitly[AdsReadable[T]]

    override def fromBytes(bytes: Array[Byte]): Seq[T] = {
      (0 to (nrElements - 1))
        .map(i => bytes.slice(i * tReadable.size, (i + 1) * tReadable.size))
        .map(tReadable.fromBytes)
    }

    override def size: Int = tReadable.size * nrElements
  }

  private def withByteBuffer[T](defaultSize: Int)(f: ByteBuffer => T): AdsReadable[T] = new AdsReadable[T] {
    override def fromBytes(bytes: Array[Byte]): T = f(ByteBuffer.wrap(bytes))

    override def size: Int = defaultSize
  }
}

object CustomDataTypeReadableExample {

  import cats.syntax.apply._
  import DefaultReadables._
  import DefaultWritables._

  // How to define a codec for a custom data structure (case class)
  // It will be read and written as 3*4 bytes
  case class MyDummyObject(a: Int, b: Int, c: Int)

  val myDummyObjectReadable: AdsCodec[MyDummyObject] =
    (AdsCodec.of[Int], AdsCodec.of[Int], AdsCodec.of[Int])
      .imapN(MyDummyObject.apply)(Function.unlift(MyDummyObject.unapply))
}
