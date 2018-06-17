package com.vroste.adsclient.codec

import cats.{Invariant, Semigroupal}

/**
  * Defines how to convert a value of type T to and from the ADS byte array representation
  *
  * Forms an invariant functor. AdsCodecs can be combined using apply syntax
  * @tparam T
  */
trait AdsCodec[T] extends AdsReadable[T] with AdsWritable[T]

object AdsCodec {
  def of[T: AdsReadable: AdsWritable]: AdsCodec[T] = apply(implicitly[AdsReadable[T]], implicitly[AdsWritable[T]])

  def apply[T](readable: AdsReadable[T], writable: AdsWritable[T]): AdsCodec[T] = new AdsCodec[T] {
    def fromBytes(bytes: Array[Byte]): T = readable.fromBytes(bytes)
    def size: Int = readable.size

    def toBytes(value: T): Array[Byte] = writable.toBytes(value)
    def size(value: T): Int = writable.size(value)
  }

  implicit val functorInstance: Invariant[AdsCodec] = new Invariant[AdsCodec] {
    override def imap[A, B](fa: AdsCodec[A])(f: A => B)(g: B => A): AdsCodec[B] =
      AdsCodec(fa.map(f), fa.contramap(g))
  }

  implicit val semigroupal: Semigroupal[AdsCodec] = new Semigroupal[AdsCodec] {
    override def product[A, B](fa: AdsCodec[A], fb: AdsCodec[B]): AdsCodec[(A, B)] =
      AdsCodec(Semigroupal[AdsReadable].product(fa, fb), Semigroupal[AdsWritable].product(fa, fb))
  }

  import DefaultReadables._
  import DefaultWritables._

  implicit val intCodec: AdsCodec[Int] = AdsCodec.of[Int]
}
