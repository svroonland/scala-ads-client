package nl.vroste.adsclient

trait TestUtil {
  val settings = AdsConnectionSettings(
    amsNetIdTarget = AmsNetId.fromString("10.211.55.3.1.1"),
    amsPortTarget = 851,
    amsNetIdSource = AmsNetId.fromString("192.168.0.101.1.1"),
    amsPortSource = 39205,
    hostname = "192.168.0.104"
  )
}

object TestUtil extends TestUtil
