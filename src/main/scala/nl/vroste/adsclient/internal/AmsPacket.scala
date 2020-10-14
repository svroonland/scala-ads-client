package nl.vroste.adsclient.internal

import nl.vroste.adsclient.internal.codecs.AmsCodecs.amsPacketCodec

case class AmsPacket(header: AmsHeader) {
  def debugString: String =
    amsPacketCodec
      .encode(this)
      .map(_.toByteArray)
      .getOrElse(throw new IllegalArgumentException("Unable to encode"))
      .map("%02X" format _)
      .mkString(" ")
}
