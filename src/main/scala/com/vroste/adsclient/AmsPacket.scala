package com.vroste.adsclient

import scodec.bits.BitVector
import scodec.{Codec, Encoder}

case class AmsPacket(header: AmsHeader) {
  def debugString: String = Encoder[AmsPacket].encode(this).map(_.toByteArray)
    .getOrElse(throw new IllegalArgumentException("Unable to encode"))
    .map("%02X" format _).mkString(" ")
}

object AmsPacket {

  import scodec.codecs._

  implicit val codec: Codec[AmsPacket] = (
    ("reserved1" | constant(BitVector.lowByte)) ~>
      ("reserved2" | constant(BitVector.lowByte)) ~>
      variableSizeBytes(
        size = ("length" | uint32L).xmap[Int](_.toInt, _.toLong),
        value = "header" | Codec[AmsHeader]))
    .xmap[AmsPacket](AmsPacket.apply, Function.unlift(AmsPacket.unapply))
}
