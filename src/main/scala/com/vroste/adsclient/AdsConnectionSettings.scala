package com.vroste.adsclient

import scala.concurrent.duration.{FiniteDuration, _}

case class AdsConnectionSettings(amsNetIdTarget: AmsNetId,
                                 amsPortTarget: Int,
                                 amsNetIdSource: AmsNetId,
                                 amsPortSource: Int,
                                 hostname: String,
                                 port: Int = 48898, timeout: FiniteDuration = 5.seconds)
