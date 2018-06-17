package com.vroste.adsclient.codec

import java.nio.{ByteBuffer, ByteOrder}

object DefaultWritables {
  // BYTE
  implicit val byteWritable: AdsWritable[Byte] = withByteBuffer(1)(_.put(_))

  // INT / WORD
  implicit val shortWritable: AdsWritable[Short] = withByteBuffer(2)(_.putShort(_))

  // UINT
  implicit val charWritable: AdsWritable[Char] = withByteBuffer(2)(_.putChar(_))

  // DINT / DWORD
  implicit val intWritable: AdsWritable[Int] = withByteBuffer(4)(_.putInt(_))

  // BOOL
  implicit val boolWritable: AdsWritable[Boolean] = byteWritable.contramap(x => if (x) 1.toByte else 0.toByte)

  // REAL
  implicit val floatWritable: AdsWritable[Float] = withByteBuffer(4)(_.putFloat(_))

  // LREAL
  implicit val doubleWritable: AdsWritable[Double] = withByteBuffer(8)(_.putDouble(_))

  // STRING
  implicit val stringWritable: AdsWritable[String] = AdsWritable[String](_.length + 1) {
    _.getBytes("ASCII") :+ 0x00.toByte
  }

  def arrayWritable[T: AdsWritable](nrElements: Int): AdsWritable[Array[T]] = new AdsWritable[Array[T]] {
    val writableT                                      = implicitly[AdsWritable[T]]
    override def toBytes(value: Array[T]): Array[Byte] = value.flatMap(writableT.toBytes)
    override def size(value: Array[T]): Int            = value.map(writableT.size).sum
  }

  // TODO TIME, TIME_OF_DAY, DATE, DATE_AND_TIME, ENUMcomp

  def withByteBuffer[T](size: Int)(f: (ByteBuffer, T) => ByteBuffer): AdsWritable[T] = AdsWritable[T](_ => size) {
    value =>
      f(ByteBuffer.allocate(size), value).array()
  }

}
