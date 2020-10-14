package nl.vroste.adsclient.internal

object IndexGroups {
  val GetSymHandleByName      = 0xf003
  val ReadWriteSymValByHandle = 0xf005
  val ReleaseSymHandle        = 0xf006
  val SumRead                 = 0xf080
  val SumWrite                = 0xf081
  val SumWriteRead            = 0xf082

  val AdsState = 0xf100
}
