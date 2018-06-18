package com.vroste.adsclient

import java.time.{Duration, Instant}
import java.time.temporal.ChronoUnit

import scodec.bits.ByteVector
import scodec.{Attempt, Codec, Err}
import scodec.codecs._

/**
  * Responses from the ADS server
  *
  * Mostly responses to commands, but also autonomously sent notifications
  */
sealed trait AdsResponse {
  val errorCode: Long
}

object AdsResponse {
  case class AdsAddDeviceNotificationCommandResponse(errorCode: Long, notificationHandle: Long) extends AdsResponse
  case class AdsDeleteDeviceNotificationCommandResponse(errorCode: Long)                       extends AdsResponse
  case class AdsWriteCommandResponse(errorCode: Long)                                          extends AdsResponse
  case class AdsReadCommandResponse(errorCode: Long, data: ByteVector)                        extends AdsResponse
  case class AdsWriteReadCommandResponse(errorCode: Long, data: ByteVector)                                   extends AdsResponse
//  case class AdsReadDeviceInfoCommandResponse(errorCode: Int, deviceInfo: AdsDeviceInfo)      extends AdsResponse
//  case class AdsReadStateCommandResponse(errorCode: Int, state: AdsState, deviceState: Short) extends AdsResponse
  case class AdsNotificationResponse(stamps: List[AdsStampHeader])                             extends AdsResponse {
    override val errorCode: Long = 0
  }

  val uint32LAsInt = uint32L.xmap[Int](_.toInt, _.toLong)

  val adsReadCommandResponseCodec: Codec[AdsReadCommandResponse] =
    (uint32L :: variableSizeBytesLong("length" | uint32L, bytes)).as

  val adsWriteReadCommandResponseCodec: Codec[AdsWriteReadCommandResponse] =
    (uint32L :: variableSizeBytesLong("length" | uint32L, bytes)).as

  val adsWriteCommandResponseCodec: Codec[AdsWriteCommandResponse] =
    uint32L.as

  val adsAddDeviceNotificationResponseCodec: Codec[AdsAddDeviceNotificationCommandResponse] =
    (uint32L :: uint32L).as

  val adsDeleteDeviceNotificationCommandResponseCodec: Codec[AdsDeleteDeviceNotificationCommandResponse] =
    uint32L.as

  val adsNotificationSampleCodec: Codec[AdsNotificationSample] =
    (uint32L :: variableSizeBytesLong("samplesSize" | uint32L, bytes)).as

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

object WindowsFiletime {
  lazy val timestampZero: Instant = Instant.parse("1601-01-01T00:00:00Z")

  def filetimeToInstant(fileTime: Long): Instant = {
    val duration = Duration.of(fileTime / 10, ChronoUnit.MICROS).plus(fileTime % 10 * 100, ChronoUnit.NANOS)
    timestampZero.plus(duration)
  }

  def instantToFiletime(instant: Instant): Long =
    timestampZero.until(instant, ChronoUnit.NANOS) // Something like this

  val codec: Codec[Instant] = uint32L.xmap(filetimeToInstant, instantToFiletime)
}

case class AdsStampHeader(timestamp: Instant, samples: List[AdsNotificationSample])
case class AdsNotificationSample(handle: Long, data: ByteVector)
