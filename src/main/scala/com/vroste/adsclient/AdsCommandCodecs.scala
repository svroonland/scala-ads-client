package com.vroste.adsclient

import scodec.{Attempt, Codec, Err}

trait AdsCommandCodecs {
  import AdsCommand._
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


  val codec: Codec[AdsCommand] = (
    addDeviceNotificationCommandCodec :+:
      writeReadCommandCodec :+:
      writeCommandCodec :+:
      readCommandCodec :+:
      deleteDeviceNotificationCommandCodec
    ).choice.as[AdsCommand]

  def codecForCommandId(commandId: Int): Codec[Either[AdsCommand, AdsResponse]] =
    codec.exmap(r => Attempt.successful(Left(r)), {
      case Left(r) => Attempt.successful(r)
      case Right(_) => Attempt.failure(Err(s"not a value of type AdsCommand"))
    })
}

object AdsCommandCodecs extends AdsCommandCodecs
