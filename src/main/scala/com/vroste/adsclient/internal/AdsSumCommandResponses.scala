package com.vroste.adsclient.internal

object AdsSumCommandResponses {

  import AdsResponse._

  case class AdsSumWriteReadCommandResponse(responses: List[AdsWriteReadCommandResponse])

  case class AdsSumWriteCommandResponse(responses: List[AdsWriteCommandResponse])

  // No read command response as we don't want to have a list of the individual responses but decode the HList of values directly
}
