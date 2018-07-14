package com.vroste.adsclient.internal.codecs

import com.vroste.adsclient.AmsNetId
import com.vroste.adsclient.internal.{AdsCommand, AdsResponse, AmsHeader, AmsPacket}
import scodec.Codec
import scodec.bits.{BitVector, ByteVector}
import shapeless.HNil

trait AmsCodecs {

  import scodec.codecs._
  import shapeless.::

  implicit val amsNetIdCodec: Codec[AmsNetId] = {
    val byteVectorToNetId: ByteVector => AmsNetId =
      bytes =>
        AmsNetId(bytes.toSeq.map(_.toChar.toString).mkString("."))

    val netIdToByteVector: AmsNetId => ByteVector =
      amsNetId => ByteVector(amsNetId.value.split('.').map(_.toInt.toByte))

    bytes(6).xmap(byteVectorToNetId, netIdToByteVector)
  }

  implicit val amsHeaderCodec: Codec[AmsHeader] = {
    val commandAndOtherStuffCodec = (("commandId" | uint16L) ~ ("stateFlags" | uint16L)).flatZip { case (commandId, stateFlags) =>
      val commandOrResponseCodec: Codec[Either[AdsCommand, AdsResponse]] =
        stateFlags match {
          case 0x0005 =>
            // Response
            AdsResponseCodecs.codecForCommandId(commandId)
          case 0x0004 if commandId == 8 => // ADS notification
            AdsResponseCodecs.codecForCommandId(commandId)
          case 0x0004 =>
            // Request
            AdsCommandCodecs.codecForCommandId(commandId)
        }

      variableSizePrefixedBytesLong(
        size = "dataLength" | uint32L,
        prefix = ("errorCode" | uint32L) ~ ("invokeId" | uint32),
        value = commandOrResponseCodec
      )
    }.flattenLeftPairs


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

  implicit val amsPacketCodec: Codec[AmsPacket] = (
    ("reserved1" | constant(BitVector.lowByte)) ~>
      ("reserved2" | constant(BitVector.lowByte)) ~>
      variableSizeBytes(
        size = ("length" | uint32L).xmap[Int](_.toInt, _.toLong),
        value = "header" | Codec[AmsHeader]))
    .xmap[AmsPacket](AmsPacket.apply, Function.unlift(AmsPacket.unapply))
}

object AmsCodecs extends AmsCodecs
