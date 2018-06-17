package com.vroste.adsclient

import com.vroste.adsclient.AdsCommand.AdsWriteReadCommand
import org.scalatest.{FlatSpec, MustMatchers}
import scodec.Codec

/**
  * Tests for the binary encodings of the ADS protocol
  */
class ScodecSpec extends FlatSpec with MustMatchers {
  val amsNetId = AmsNetId("10.211.55.3.1.1")

  it must "encode AMS net IDs" in {
    println(Codec[AmsNetId].encode(amsNetId))
  }

  it must "encode a write read command" in {
    val settings = AdsConnectionSettings(amsNetId, 851, amsNetId, 39205, "10.255.33.1", 48898)
    val varName = "MAIN.var1"
    val command = AdsWriteReadCommand(0x0000F003, 0x00000000, 4, AdsCommandClient.asAdsString(varName))

    val packet = AmsPacket(AmsHeader(settings.amsNetIdTarget, settings.amsPortTarget, settings.amsNetIdSource, settings.amsPortSource, AdsCommand.commandId(command), 4, 0, 1, Left(command)))

    println(Codec.encode(packet).map(_.toHex))

    // x03f000000000000004000000 0a 00 00 00 4d 41 49 4e 2e 76 61 72 31 00

    // Successful(3e0000000ad337030101530300000ad33703010189800000090004001a000000000000000000000103f0000000000000040000000a0000004d41494e2e7661723100)

    // 00 00 3e 00 00 00 // length = 62
    // 0a d3 37 03 01 01 53 03 00 00
    // 0a d3 37 03 01 01 89 80 0000090004001a0000000000000
    // 00000000103f0000000000000040000000a0000004d41494e2e7661723100) }

    // 00 00 3a 00 00 00
    // 0a d3 37 03 01 01 53 03
    // 0a d3 37 03 01 01 89 80
    // 09 00 04 00 1a 00 00 00
    // 00 00 00 00 00 00 00 01
    // 03 f0 00 00 00 00 00 00
    // 04 00 00 00 0a 00 00 00
    // 4d 41 49 4e 2e 76 61 72 31 00 // Var1
//    )
  }
}
