package com.vroste.adsclient

import scodec.Codec
import scodec.bits.{BitVector, ByteVector}

// See https://infosys.beckhoff.de/index.php?content=../content/1031/tc3_adscommon/html/tcadsamsspec_amstcppackage.htm&id=
case class AmsHeader(amsNetIdTarget: AmsNetId,
                     amsPortTarget: Int,
                     amsNetIdSource: AmsNetId,
                     amsPortSource: Int,
                     commandId: Short,
                     stateFlags: Short,
                     dataLength: Int,
                     errorCode: Int,
                     invokeId: Int,
                     data: Either[AdsCommand, AdsResponse])

object AmsHeader {

  import scodec.codecs._

  val dataLengthAndRestCodec = variableSizePrefixedBytesLong(
    size = "dataLength" | uint32L,
    prefix = ("errorCode" | uint32L) ~ ("invokeId" | uint32),
    value = Codec[Either[AdsCommand, AdsResponse]]
  )

  implicit val codec: Codec[AmsHeader] =
    (("netId" | Codec[AmsNetId]) ~
      ("targetPort" | uint32L) ~
      ("netIdSource" | Codec[AmsNetId]) ~
      ("portSource" | uint32L) ~
      ("commandId" | uint16L) ~
      ("stateFlags" | uint16L) ~
      dataLengthAndRestCodec
      ).flattenLeftPairs.xmapc(_.tupled match {
      case (amsNetIdTarget, targetPort, netIdSource, portSource, commandId, stateFlags, ())
    })
}
