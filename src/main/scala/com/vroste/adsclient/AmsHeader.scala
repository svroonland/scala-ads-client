package com.vroste.adsclient

import scodec.Codec
import shapeless.HNil

// See https://infosys.beckhoff.de/index.php?content=../content/1031/tc3_adscommon/html/tcadsamsspec_amstcppackage.htm&id=
case class AmsHeader(amsNetIdTarget: AmsNetId,
                     amsPortTarget: Int,
                     amsNetIdSource: AmsNetId,
                     amsPortSource: Int,
                     commandId: Int,
                     stateFlags: Int,
                     errorCode: Int,
                     invokeId: Int,
                     data: Either[AdsCommand, AdsResponse])

object AmsHeader {

  import scodec.codecs._

  val commandAndOtherStuffCodec = (("commandId" | uint16L) ~ ("stateFlags" | uint16L)).flatZip { case (commandId, stateFlags) =>
    val commandOrResponseCodec: Codec[Either[AdsCommand, AdsResponse]] =
      stateFlags match {
        case 0x0005 =>
          // Response
          AdsResponse.codecForCommandId(commandId)
        case 0x004 =>
          // Request
          AdsCommand.codecForCommandId(commandId)
      }

    variableSizePrefixedBytesLong(
      size = "dataLength" | uint32L,
      prefix = ("errorCode" | uint32L) ~ ("invokeId" | uint32),
      value = commandOrResponseCodec
    )
  }.flattenLeftPairs

  import shapeless.::

  implicit val codec: Codec[AmsHeader] =
    (("netId" | Codec[AmsNetId]) ~
      ("targetPort" | uint16L) ~
      ("netIdSource" | Codec[AmsNetId]) ~
      ("portSource" | uint16L) ~
      commandAndOtherStuffCodec
      ).flattenLeftPairs.xmapc {
      case amsNetIdTarget :: targetPort :: netIdSource :: portSource :: (commandId :: stateFlags :: ((errorCode, invokeId), payload) :: HNil) :: HNil =>
        AmsHeader(amsNetIdTarget, targetPort.toInt, netIdSource, portSource.toInt, commandId.toShort, stateFlags, errorCode.toInt, invokeId.toInt, payload)
    } { p =>
      p.amsNetIdTarget :: p.amsPortTarget :: p.amsNetIdSource :: p.amsPortSource :: (p.commandId :: p.stateFlags :: ((p.errorCode.toLong, p.invokeId.toLong), p.data) :: HNil) :: HNil
    }
}
