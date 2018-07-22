package com.vroste.adsclient.internal

import com.vroste.adsclient.ErrorCode

object AdsSumCommandResponses {

  import AdsResponse._

  case class AdsSumWriteReadCommandResponse(responses: List[AdsWriteReadCommandResponse])

  case class AdsSumWriteCommandResponse(responses: List[AdsWriteCommandResponse]) {
    val errorCodes: Seq[ErrorCode] = responses.map(_.errorCode)
  }

  // No read command response as we don't want to have a list of the individual responses but decode the HList of values directly
}
