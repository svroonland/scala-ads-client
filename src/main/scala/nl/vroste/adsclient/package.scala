package nl.vroste

import java.time.Instant

import scala.concurrent.duration.{FiniteDuration, _}
import enumeratum._

/**
  * Minor data types, enums
  *
  * Import nl.vroste.adsclient._ to get access to all data types needed for working with the client
  */
package object adsclient extends AdsCodecs {
  type ErrorCode = Long

  case class AmsNetId(value: String) extends AnyVal {
    def asString: String = value
  }

  object AmsNetId {
    def fromString(value: String): AmsNetId = AmsNetId(value)
  }

  case class AdsConnectionSettings(amsNetIdTarget: AmsNetId,
                                   amsPortTarget: Int,
                                   amsNetIdSource: AmsNetId,
                                   amsPortSource: Int,
                                   hostname: String,
                                   port: Int = 48898, timeout: FiniteDuration = 5.seconds)

  sealed abstract class AdsState(value: Short) extends EnumEntry

  object AdsState extends Enum[AdsState]{
    override def values = findValues

    case object Invalid extends AdsState(0)
    case object Idle extends AdsState(1)
    case object Reset extends AdsState(2)
    case object Init extends AdsState(3)
    case object Start extends AdsState(4)
    case object Run extends AdsState(5)
    case object Stop extends AdsState(6)
    case object SaveConfiguration extends AdsState(7)
    case object LoadConfiguration extends AdsState(8)
    case object PowerFailure extends AdsState(9)
    case object PowerGood extends AdsState(10)
    case object Error extends AdsState(11)
    case object Shutdown extends AdsState(12)
    case object Suspend extends AdsState(13)
    case object Resume extends AdsState(14)
    case object Config extends AdsState(15)
    case object Reconfig extends AdsState(16)
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
    case object Cyclic extends AdsTransmissionMode
  }

  case class VariableHandle(value: Long) extends AnyVal

  case class NotificationHandle(value: Long) extends AnyVal

  case class AdsClientException(message: String) extends Exception(message)
}
