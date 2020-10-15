package nl.vroste.adsclient
import java.time.Instant

import enumeratum.{ Enum, EnumEntry }
import scodec.Err

import zio.duration._

case class AmsNetId(value: String) extends AnyVal {
  def asString: String = value
}

sealed trait AdsClientError

case object Timeout                         extends AdsClientError
case class EncodingError(e: Err)            extends AdsClientError
case class DecodingError(e: Err)            extends AdsClientError
case object UnexpectedResponse              extends AdsClientError
case class AdsClientException(e: Throwable) extends AdsClientError
case class AdsErrorResponse(code: Long)     extends AdsClientError
case object UnknownNotificationHandle       extends AdsClientError

case object ResponseTimeout extends AdsClientError

object AmsNetId {
  def fromString(value: String): AmsNetId = AmsNetId(value)
}

case class AdsConnectionSettings(
  amsNetIdTarget: AmsNetId,
  amsPortTarget: Int,
  amsNetIdSource: AmsNetId,
  amsPortSource: Int,
  hostname: String,
  port: Int = 48898,
  timeout: zio.duration.Duration = 5.seconds
)

sealed abstract class AdsState extends EnumEntry

object AdsState extends Enum[AdsState] {
  override def values = findValues

  case object Invalid           extends AdsState
  case object Idle              extends AdsState
  case object Reset             extends AdsState
  case object Init              extends AdsState
  case object Start             extends AdsState
  case object Run               extends AdsState
  case object Stop              extends AdsState
  case object SaveConfiguration extends AdsState
  case object LoadConfiguration extends AdsState
  case object PowerFailure      extends AdsState
  case object PowerGood         extends AdsState
  case object Error             extends AdsState
  case object Shutdown          extends AdsState
  case object Suspend           extends AdsState
  case object Resume            extends AdsState
  case object Config            extends AdsState
  case object Reconfig          extends AdsState
}

/**
 * A notification of a change in a variable
 *
 * @param value Reported value of the variable
 * @param timestamp Time as reported by the ADS server
 * @tparam T Type of the value
 */
case class AdsNotification[T](value: T, timestamp: Instant)

case class AdsDeviceInfo(majorVersion: Byte, minorVersion: Byte, versionBuild: Short, deviceName: String)

sealed trait AdsTransmissionMode extends EnumEntry

object AdsTransmissionMode extends Enum[AdsTransmissionMode] {
  override def values = findValues

  case object OnChange extends AdsTransmissionMode
  case object Cyclic   extends AdsTransmissionMode
}

case class VariableHandle(value: Long) extends AnyVal

case class NotificationHandle(value: Long) extends AnyVal
