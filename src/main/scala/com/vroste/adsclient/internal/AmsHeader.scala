package com.vroste.adsclient.internal

import com.vroste.adsclient.AmsNetId

// See https://infosys.beckhoff.de/index.php?content=../content/1031/tc3_adscommon/html/tcadsamsspec_amstcppackage.htm&id=
case class AmsHeader(amsNetIdTarget: AmsNetId,
                     amsPortTarget: Int,
                     amsNetIdSource: AmsNetId,
                     amsPortSource: Int,
                     commandId: Int,
                     stateFlags: Int,
                     errorCode: Int,
                     invokeId: Int,
                     data: Either[AdsCommand, AdsResponse])
