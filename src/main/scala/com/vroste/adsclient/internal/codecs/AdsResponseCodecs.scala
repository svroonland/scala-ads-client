package com.vroste.adsclient.internal.codecs

import com.vroste.adsclient.internal._
import scodec.codecs.{StringEnrichedWithCodecContextSupport, bits, listOfN, uint32L, variableSizeBytesLong}
import scodec.{Attempt, Codec, Err}

trait AdsResponseCodecs {
  import AdsResponse._

  val errorCodeCodec: Codec[Long] = uint32L

  val uint32LAsInt = uint32L.xmap[Int](_.toInt, _.toLong)

  val adsReadCommandResponseCodec: Codec[AdsReadCommandResponse] =
    (errorCodeCodec :: variableSizeBytesLong("length" | uint32L, bits)).as

  val adsWriteReadCommandResponseCodec: Codec[AdsWriteReadCommandResponse] =
    (errorCodeCodec :: variableSizeBytesLong("length" | uint32L, bits)).as

  val adsWriteCommandResponseCodec: Codec[AdsWriteCommandResponse] =
    errorCodeCodec.as

  val adsAddDeviceNotificationResponseCodec: Codec[AdsAddDeviceNotificationCommandResponse] =
    (errorCodeCodec :: uint32L).as

  val adsDeleteDeviceNotificationCommandResponseCodec: Codec[AdsDeleteDeviceNotificationCommandResponse] =
    errorCodeCodec.as

  val adsNotificationSampleCodec: Codec[AdsNotificationSample] =
    (uint32L :: variableSizeBytesLong("samplesSize" | uint32L, bits)).as

  val adsStampHeaderCodec: Codec[AdsStampHeader] =
    (WindowsFiletime.codec :: listOfN("samples" | uint32LAsInt, adsNotificationSampleCodec)).as

  val adsDeviceNotificationCodec: Codec[AdsNotificationResponse] =
    variableSizeBytesLong("length" | uint32L,
      listOfN("stamps" | uint32LAsInt, adsStampHeaderCodec)
    ).hlist.as

  val commandIdToResponseCodec: Int => Codec[AdsResponse] = {
    case 2 =>
      adsReadCommandResponseCodec.upcast
    case 3 =>
      adsWriteCommandResponseCodec.upcast
    case 6 =>
      adsAddDeviceNotificationResponseCodec.upcast
    case 7 =>
      adsDeleteDeviceNotificationCommandResponseCodec.upcast
    case 8 =>
      adsDeviceNotificationCodec.upcast
    case 9 =>
      adsWriteReadCommandResponseCodec.upcast
    case unknownCommandId =>
      scodec.codecs.fail(Err(s"Unknown command id ${unknownCommandId}"))
  }

  def codecForCommandId(commandId: Int): Codec[Either[AdsCommand, AdsResponse]] =
    commandIdToResponseCodec(commandId).exmap(r => Attempt.successful(Right(r)), {
      case Right(r) => Attempt.successful(r)
      case Left(_) => Attempt.failure(Err(s"not a value of type AdsResponse"))
    })
}

object AdsResponseCodecs extends AdsResponseCodecs
