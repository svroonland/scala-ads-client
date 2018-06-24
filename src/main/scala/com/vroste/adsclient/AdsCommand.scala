package com.vroste.adsclient

import com.vroste.adsclient.AdsResponse.{AdsAddDeviceNotificationCommandResponse, AdsDeleteDeviceNotificationCommandResponse, AdsReadCommandResponse, AdsWriteReadCommandResponse}
import scodec.{Attempt, Codec, Err}
import scodec.bits.ByteVector

/**
  * Commands to the ADS server
  */
sealed trait AdsCommand {
  self =>
  type ResponseType
}

object AdsCommand {

  case class AdsReadCommand(indexGroup: Long, indexOffset: Long, readLength: Long) extends AdsCommand {
    override type ResponseType = AdsReadCommandResponse
  }

  case class AdsWriteCommand(indexGroup: Long, indexOffset: Long, values: ByteVector) extends AdsCommand {
    override type ResponseType = AdsWriteReadCommandResponse.type
  }

  case class AdsWriteReadCommand(indexGroup: Long, indexOffset: Long, readLength: Long, values: ByteVector)
    extends AdsCommand {
    override type ResponseType = AdsWriteReadCommandResponse
  }

  case class AdsAddDeviceNotificationCommand(indexGroup: Long,
                                             indexOffset: Long,
                                             readLength: Long,
                                             transmissionMode: AdsTransmissionMode,
                                             maxDelay: Long,
                                             cycleTime: Long)
    extends AdsCommand {
    override type ResponseType = AdsAddDeviceNotificationCommandResponse
  }

  case class AdsDeleteDeviceNotificationCommand(notificationHandle: Long) extends AdsCommand {
    override type ResponseType = AdsDeleteDeviceNotificationCommandResponse.type
  }

  def commandId(c: AdsCommand): Short = c match {
    case AdsReadCommand(_, _, _) => 0x0002
    case AdsWriteCommand(_, _, _) => 0x0003
    case AdsAddDeviceNotificationCommand(_, _, _, _, _, _) => 0x0006
    case AdsDeleteDeviceNotificationCommand(_) => 0x0007
    case AdsWriteReadCommand(_, _, _, _) => 0x0009
  }

  import scodec.codecs._

  implicit val adsTransmissionModeCodec: Codec[AdsTransmissionMode] = uint32L.xmapc[AdsTransmissionMode] {
    l => if (l == 3L) AdsTransmissionMode.Cyclic else AdsTransmissionMode.OnChange
  } {
    case AdsTransmissionMode.Cyclic => 3L
    case AdsTransmissionMode.OnChange => 4L
  }

  implicit val readCommandCodec: Codec[AdsReadCommand] =
    (uint32L :: uint32L :: uint32L)
      .as[AdsReadCommand]

  implicit val writeCommandCodec: Codec[AdsWriteCommand] =
    (uint32L :: uint32L :: variableSizeBytesLong(uint32L, bytes))
      .as[AdsWriteCommand]

  implicit val writeReadCommandCodec: Codec[AdsWriteReadCommand] =
    (uint32L :: uint32L :: uint32L :: variableSizeBytesLong(uint32L, bytes)).as[AdsWriteReadCommand]

  implicit val addDeviceNotificationCommandCodec: Codec[AdsAddDeviceNotificationCommand] =
    (uint32L ~ uint32L ~ uint32L ~ Codec[AdsTransmissionMode] ~ uint32L ~ uint32L <~ ignore(16 * 8))
      .flattenLeftPairs
      .as[AdsAddDeviceNotificationCommand]

  implicit val deleteDeviceNotificationCommandCodec: Codec[AdsDeleteDeviceNotificationCommand] =
    uint32L.as[AdsDeleteDeviceNotificationCommand]

  implicit val codec: Codec[AdsCommand] = (addDeviceNotificationCommandCodec :+: writeReadCommandCodec :+: writeCommandCodec :+: readCommandCodec :+: deleteDeviceNotificationCommandCodec).choice.as[AdsCommand]

  def codecForCommandId(commandId: Int): Codec[Either[AdsCommand, AdsResponse]] =
    codec.exmap(r => Attempt.successful(Left(r)), {
      case Left(r) => Attempt.successful(r)
      case Right(_) => Attempt.failure(Err(s"not a value of type AdsCommand"))
    })
}
