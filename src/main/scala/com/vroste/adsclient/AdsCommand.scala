package com.vroste.adsclient

import com.vroste.adsclient.AdsResponse.{AdsAddDeviceNotificationCommandResponse, AdsDeleteDeviceNotificationCommandResponse, AdsReadCommandResponse, AdsWriteReadCommandResponse}
import scodec.{Attempt, Codec, Encoder, Err}
import scodec.bits.ByteVector

/**
  * Commands to the ADS server
  */
sealed trait AdsCommand

object AdsCommand {

  case class AdsReadCommand(indexGroup: Long, indexOffset: Long, readLength: Long) extends AdsCommand {
  }

  case class AdsWriteCommand(indexGroup: Long, indexOffset: Long, values: ByteVector) extends AdsCommand {
  }

  case class AdsWriteReadCommand(indexGroup: Long, indexOffset: Long, readLength: Long, values: ByteVector)
    extends AdsCommand {
  }

  case class AdsAddDeviceNotificationCommand(indexGroup: Long,
                                             indexOffset: Long,
                                             readLength: Long,
                                             transmissionMode: AdsTransmissionMode,
                                             maxDelay: Long,
                                             cycleTime: Long)
    extends AdsCommand {
  }

  case class AdsDeleteDeviceNotificationCommand(notificationHandle: Long) extends AdsCommand {
  }

  case class AdsSumReadCommand(commands: Seq[AdsReadCommand]) extends AdsCommand

  case class AdsSumWriteCommand(commands: Seq[AdsWriteCommand]) extends AdsCommand

  case class AdsSumWriteReadCommand(commands: Seq[AdsWriteReadCommand]) extends AdsCommand

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

  implicit val sumCommandCodec: Codec[AdsSumWriteReadCommand] = {
    val encoder: Encoder[AdsSumWriteReadCommand] = AdsCommand.writeReadCommandCodec.asEncoder.econtramap[AdsSumWriteReadCommand] { sumCommand =>
      for {
        values <- Codec.encode(sumCommand.commands.toList)
      } yield AdsWriteReadCommand(0xf080, sumCommand.commands.length, sumCommand.commands.map(_.readLength).sum, values.toByteVector)
    }

    val decoder = AdsCommand.writeReadCommandCodec.asDecoder.emap { writeReadCommand =>
      Codec[List[AdsWriteReadCommand]].decodeValue(writeReadCommand.values.toBitVector)
        .map(_.toSeq)
        .map(AdsSumWriteReadCommand(_))
    }

    Codec(encoder, decoder)
  }

  implicit val codec: Codec[AdsCommand] = (addDeviceNotificationCommandCodec :+: writeReadCommandCodec :+: writeCommandCodec :+: readCommandCodec :+: deleteDeviceNotificationCommandCodec).choice.as[AdsCommand]


  def codecForCommandId(commandId: Int): Codec[Either[AdsCommand, AdsResponse]] =
    codec.exmap(r => Attempt.successful(Left(r)), {
      case Left(r) => Attempt.successful(r)
      case Right(_) => Attempt.failure(Err(s"not a value of type AdsCommand"))
    })
}
