package nl.vroste.adsclient.internal

import java.time.Instant

import scodec.bits.BitVector
import scodec.{Attempt, Decoder}

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

  case class AdsDeleteDeviceNotificationCommandResponse(errorCode: Long) extends AdsResponse

  case class AdsWriteCommandResponse(errorCode: Long) extends AdsResponse

  case class AdsReadCommandResponse(errorCode: Long, data: BitVector) extends AdsResponse

  case class AdsWriteReadCommandResponse(errorCode: Long, data: BitVector) extends AdsResponse {
    def decode[T: Decoder]: Attempt[T] = Decoder[T].decodeValue(data)
  }

  //  case class AdsReadDeviceInfoCommandResponse(errorCode: Int, deviceInfo: AdsDeviceInfo)      extends AdsResponse
  //  case class AdsReadStateCommandResponse(errorCode: Int, state: AdsState, deviceState: Short) extends AdsResponse
  case class AdsNotificationResponse(stamps: List[AdsStampHeader]) extends AdsResponse {
    override val errorCode: Long = 0
  }
}

case class AdsStampHeader(timestamp: Instant, samples: List[AdsNotificationSample])
case class AdsNotificationSample(handle: Long, data: BitVector)
