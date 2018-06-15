package com.vroste.adsclient.codec

import cats.{Functor, Semigroupal}

/**
  * Defines how to read a value of type T from an array of bytes as received from an ADS server
  *
  * @tparam T
  */
trait AdsReadable[T] { self =>
  def fromBytes(bytes: Array[Byte]): T

  // The size of a value of this type in TwinCAT
  def size: Int

  def map[U](f: T => U): AdsReadable[U] = new AdsReadable[U] {
    override def fromBytes(bytes: Array[Byte]): U = f(self.fromBytes(bytes))
    override def size: Int                        = self.size
  }
}

object AdsReadable {
  def of[T: AdsReadable]: AdsReadable[T] = implicitly[AdsReadable[T]]
  implicit val functorInstance: Functor[AdsReadable] = new Functor[AdsReadable] {
    override def map[A, B](fa: AdsReadable[A])(f: A => B): AdsReadable[B] = fa.map(f)
  }

  implicit val semigroupalInstance: Semigroupal[AdsReadable] = new Semigroupal[AdsReadable] {
    override def product[A, B](fa: AdsReadable[A], fb: AdsReadable[B]): AdsReadable[(A, B)] = new AdsReadable[(A, B)] {
      override def fromBytes(bytes: Array[Byte]): (A, B) = {
        val va = fa.fromBytes(bytes.take(fa.size))
        val vb = fb.fromBytes(bytes.drop(fa.size))

        (va, vb)
      }

      override def size: Int = fa.size + fb.size
    }
  }
}
