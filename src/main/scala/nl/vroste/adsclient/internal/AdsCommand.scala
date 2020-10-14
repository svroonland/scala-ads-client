package nl.vroste.adsclient.internal

import nl.vroste.adsclient.AdsTransmissionMode
import scodec.bits.BitVector

/**
 * Commands to the ADS server
 */
sealed trait AdsCommand

object AdsCommand {

  case class AdsReadCommand(indexGroup: Long, indexOffset: Long, readLength: Long) extends AdsCommand {
    override def toString: String = s"Read {indexGroup=$indexGroup, indexOffset=$indexOffset, readLength=$readLength}"
  }

  case class AdsWriteCommand(indexGroup: Long, indexOffset: Long, values: BitVector) extends AdsCommand

  case class AdsWriteReadCommand(indexGroup: Long, indexOffset: Long, readLength: Long, values: BitVector)
      extends AdsCommand

  case class AdsAddDeviceNotificationCommand(
    indexGroup: Long,
    indexOffset: Long,
    readLength: Long,
    transmissionMode: AdsTransmissionMode,
    maxDelay: Long,
    cycleTime: Long
  ) extends AdsCommand {
    override def toString: String =
      s"AddDeviceNotification {indexGroup=$indexGroup, indexOffset=$indexOffset, " +
        s"readLength=$readLength, transmissionMode=$transmissionMode, maxDelay=$maxDelay, cycleTime=$cycleTime}"
  }

  case class AdsDeleteDeviceNotificationCommand(notificationHandle: Long) extends AdsCommand {
    override def toString: String = s"DeleteDeviceNotification {notificationHandle=$notificationHandle}"
  }

  def commandId(c: AdsCommand): Int =
    c match {
      case AdsReadCommand(_, _, _)                           => 0x0002
      case AdsWriteCommand(_, _, _)                          => 0x0003
      case AdsAddDeviceNotificationCommand(_, _, _, _, _, _) => 0x0006
      case AdsDeleteDeviceNotificationCommand(_)             => 0x0007
      case AdsWriteReadCommand(_, _, _, _)                   => 0x0009
    }
}
