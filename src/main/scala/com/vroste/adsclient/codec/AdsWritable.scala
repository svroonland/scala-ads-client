package com.vroste.adsclient.codec

import cats.{Contravariant, Semigroupal}

/**
  * Defines how to convert a value of type T to a byte array that can be written to an ADS server
  *
  * AdsWritable is a contravariant functor. Multiple AdsWritables can be composed
  *
  * @tparam T
  */
trait AdsWritable[T] { self =>
  def toBytes(value: T): Array[Byte]
  def size(value: T): Int

  def contramap[U](g: U => T): AdsWritable[U] = new AdsWritable[U] {
    override def toBytes(value: U): Array[Byte] = self.toBytes(g(value))
    override def size(value: U): Int            = self.size(g(value))
  }
}

object AdsWritable {
  def of[T: AdsWritable]: AdsWritable[T] = implicitly[AdsWritable[T]]

  def apply[T](getSize: T => Int)(getBytes: T => Array[Byte]): AdsWritable[T] = new AdsWritable[T] {
    override def toBytes(value: T): Array[Byte] = getBytes(value)
    override def size(value: T): Int            = getSize(value)
  }

  implicit val functor: Contravariant[AdsWritable] = new Contravariant[AdsWritable] {
    override def contramap[A, B](fa: AdsWritable[A])(f: B => A): AdsWritable[B] = fa.contramap(f)
  }

  implicit val semigroupal: Semigroupal[AdsWritable] = new Semigroupal[AdsWritable] {
    override def product[A, B](fa: AdsWritable[A], fb: AdsWritable[B]): AdsWritable[(A, B)] =
      AdsWritable[(A, B)](s => fa.size(s._1) + fb.size(s._2)) {
        case (a, b) =>
          fa.toBytes(a) ++ fb.toBytes(b)
      }
  }

}
