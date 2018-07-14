package com.vroste.adsclient

object SumCommandResponses {

  import AdsResponse._

  case class AdsSumWriteReadCommandResponse(responses: List[AdsWriteReadCommandResponse])

  case class AdsSumWriteCommandResponse(responses: List[AdsWriteCommandResponse])

  case class AdsSumReadCommandResponse(responses: List[AdsReadCommandResponse])

}
