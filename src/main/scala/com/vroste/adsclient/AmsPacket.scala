package com.vroste.adsclient

import scodec.bits.BitVector
import scodec.{Codec, Encoder}

case class AmsPacket(length: Int, header: AmsHeader) {
  def debugString: String = Encoder[AmsPacket].encode(this).map(_.toByteArray)
    .getOrElse(throw new IllegalArgumentException("Unable to encode"))
    .map("%02X" format _).mkString(" ")
}

object AmsPacket {

  import scodec.codecs._

  implicit val codec: Codec[AmsPacket] = (
    ("reserved1" | constant(BitVector.empty)) ~>
      ("reserved2" | constant(BitVector.empty)) ~>
      ("length" | uint32L).xmap[Int](_.toInt, _.toLong) ~
      ("header" | Codec[AmsHeader])).xmap[AmsPacket](AmsPacket.apply, Function.unlift(AmsPacket.unapply(_))
  )
}
