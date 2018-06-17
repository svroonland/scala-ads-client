package com.vroste.adsclient

import java.nio.ByteBuffer

/**
  * Responses from the ADS server
  *
  * Mostly responses to commands, but also autonomously sent notifications
  */
sealed trait AdsResponse

object AdsResponse {
  case class AdsAddDeviceNotificationCommandResponse(errorCode: Int, notificationHandle: Int) extends AdsResponse
  case class AdsDeleteDeviceNotificationCommandResponse(errorCode: Int)                       extends AdsResponse
  case class AdsWriteCommandResponse(errorCode: Int)                                          extends AdsResponse
  case class AdsReadCommandResponse(errorCode: Int, data: Array[Byte])                        extends AdsResponse
  case class AdsWriteReadCommandResponse(data: Array[Byte])                                   extends AdsResponse
  case class AdsReadDeviceInfoCommandResponse(errorCode: Int, deviceInfo: AdsDeviceInfo)      extends AdsResponse
  case class AdsReadStateCommandResponse(errorCode: Int, state: AdsState, deviceState: Short) extends AdsResponse
  case class AdsNotificationResponse(stamps: Seq[AdsStampHeader])                             extends AdsResponse

  def fromPacket(packet: AmsPacket): AdsResponse = {
    val bb     = ByteBuffer.wrap(packet.header.data)

    val errorCode = bb.getInt()

    // TODO scodec
    packet.header.commandId match {
      case 1 =>
        // ADS Read Device Info
        val errorCode       = bb.getInt()
        val deviceNameBytes = new Array[Byte](16)
        val deviceInfo = AdsDeviceInfo(bb.get(), bb.get(), bb.getShort(), {
          bb.get(deviceNameBytes);
          new String(deviceNameBytes)
        })
        AdsReadDeviceInfoCommandResponse(errorCode, deviceInfo)
      case 2 =>
        // ADS Read
        val length = bb.getInt()
        val data   = bb.slice().array()

        AdsReadCommandResponse(errorCode, data)

      case 3 =>
        // ADS Write
        AdsWriteCommandResponse(errorCode)

      case 4 =>
        val adsState    = bb.getShort()
        val deviceState = bb.getShort()
        AdsReadStateCommandResponse(errorCode, AdsState(adsState), deviceState)

      case 5 =>
        // ADS Write Control, not supported
        throw new UnsupportedOperationException("ADS Write Control not supported")

      case 6 =>
        // ADS  add device notification
        val handle = bb.getInt()

        AdsAddDeviceNotificationCommandResponse(errorCode, handle)

      case 7 =>
        // ADS delete device notification
        AdsDeleteDeviceNotificationCommandResponse(errorCode)

      case 8 =>
        // ADS device notification
        val length   = bb.getInt()
        val nrStamps = bb.getInt()

        val stamps = (1 to nrStamps).map(_ => readAdsStampHeader(bb))

        AdsNotificationResponse(stamps)

    }
  }

  def readAdsStampHeader(bb: ByteBuffer): AdsStampHeader = {
    val timestamp = bb.getLong()
    val nrSamples = bb.getInt()

    val samples = (1 to nrSamples).map(_ => readAdsNotificationSample(bb))

    AdsStampHeader(timestamp, samples)
  }

  def readAdsNotificationSample(bb: ByteBuffer): AdsNotificationSample = {
    val handle = bb.getInt()
    val size   = bb.getInt()

    val data = new Array[Byte](size)
    bb.get(data)

    AdsNotificationSample(handle, data)
  }
}

case class AdsStampHeader(timestamp: Long, samples: Seq[AdsNotificationSample])
case class AdsNotificationSample(handle: Int, data: Array[Byte])
