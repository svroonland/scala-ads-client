package nl.vroste.adsclient.internal.util

import scodec.bits.BitVector
import scodec.{ Attempt, Codec, DecodeResult, Decoder, SizeBound }
import scodec.codecs.provide

object CodecUtil {

  implicit class CodecWithSizeBound[T](val codec: Codec[T]) extends AnyVal {

    /**
     * Override the sizebound
     */
    def withSizeBound(newSizeBound: SizeBound): Codec[T] =
      new Codec[T] {
        override def decode(bits: BitVector): Attempt[DecodeResult[T]] = codec.decode(bits)

        override def encode(value: T): Attempt[BitVector] = codec.encode(value)

        override def sizeBound: SizeBound = newSizeBound
      }
  }

  implicit class SequenceDecoders[T](val decoders: Seq[Decoder[T]]) extends AnyVal {

    /**
     * Turns a Seq[Decoder[T]] into a Decoder[Seq[T]]
     */
    def sequence: Decoder[Seq[T]] =
      decoders.foldLeft(provide(Seq[T]()).asDecoder) { case (accCodec, currCodec) =>
        accCodec.flatMap(acc => currCodec.map(curr => acc :+ curr))
      }
  }

  implicit class BitVectorExtensions(val bitVector: BitVector) extends AnyVal {
    def lengthInBytes = bitVector.length / 8
  }

}
