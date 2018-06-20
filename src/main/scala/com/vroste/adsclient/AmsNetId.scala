package com.vroste.adsclient

import scodec.Codec
import scodec.bits.ByteVector

case class AmsNetId(value: String) extends AnyVal {
  def asString: String = value
}

object AmsNetId {
  import scodec.codecs._

  val byteVectorToNetId: ByteVector => AmsNetId =
    bytes =>
      AmsNetId(bytes.toSeq.map(_.toChar.toString).mkString("."))

  val netIdToByteVector: AmsNetId => ByteVector =
    amsNetId => ByteVector(amsNetId.value.split('.').map(_.toInt.toByte))

  implicit val codec: Codec[AmsNetId] = bytes(6).xmap(byteVectorToNetId, netIdToByteVector)

  def fromString(value: String): AmsNetId = AmsNetId(value)
}