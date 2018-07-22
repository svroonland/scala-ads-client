package com.vroste.adsclient.internal

import com.vroste.adsclient.AdsTransmissionMode
import scodec.bits.BitVector

/**
  * Commands to the ADS server
  */
sealed trait AdsCommand

object AdsCommand {

  case class AdsReadCommand(indexGroup: Long, indexOffset: Long, readLength: Long) extends AdsCommand

  case class AdsWriteCommand(indexGroup: Long, indexOffset: Long, values: BitVector) extends AdsCommand

  case class AdsWriteReadCommand(indexGroup: Long, indexOffset: Long, readLength: Long, values: BitVector)
    extends AdsCommand

  case class AdsAddDeviceNotificationCommand(indexGroup: Long,
                                             indexOffset: Long,
                                             readLength: Long,
                                             transmissionMode: AdsTransmissionMode,
                                             maxDelay: Long,
                                             cycleTime: Long)
    extends AdsCommand

  case class AdsDeleteDeviceNotificationCommand(notificationHandle: Long) extends AdsCommand

  def commandId(c: AdsCommand): Short = c match {
    case AdsReadCommand(_, _, _) => 0x0002
    case AdsWriteCommand(_, _, _) => 0x0003
    case AdsAddDeviceNotificationCommand(_, _, _, _, _, _) => 0x0006
    case AdsDeleteDeviceNotificationCommand(_) => 0x0007
    case AdsWriteReadCommand(_, _, _, _) => 0x0009
  }
}
