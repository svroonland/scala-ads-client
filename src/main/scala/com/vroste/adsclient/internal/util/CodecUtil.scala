package com.vroste.adsclient.internal.util

import scodec.bits.BitVector
import scodec.{Attempt, Codec, DecodeResult, SizeBound}

object CodecUtil {
  implicit class CodecWithSizeBound[T](val codec: Codec[T]) extends AnyVal {
    /**
      * Override the sizebound
      */
    def withSizeBound(newSizeBound: SizeBound): Codec[T] = new Codec[T] {
      override def decode(bits: BitVector): Attempt[DecodeResult[T]] = codec.decode(bits)

      override def encode(value: T): Attempt[BitVector] = codec.encode(value)

      override def sizeBound: SizeBound = newSizeBound
    }
  }

}
