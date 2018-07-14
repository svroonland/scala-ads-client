package com.vroste.adsclient.internal

object AdsSumCommandResponses {

  import AdsResponse._

  case class AdsSumWriteReadCommandResponse(responses: List[AdsWriteReadCommandResponse])

  case class AdsSumWriteCommandResponse(responses: List[AdsWriteCommandResponse])

  case class AdsSumReadCommandResponse(responses: List[AdsReadCommandResponse])

}
