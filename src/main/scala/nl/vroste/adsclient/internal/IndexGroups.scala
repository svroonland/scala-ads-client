package nl.vroste.adsclient.internal

object IndexGroups {
  val GetSymHandleByName      = 0xf003L
  val ReadWriteSymValByHandle = 0xf005L
  val ReleaseSymHandle        = 0xf006L
  val SumRead                 = 0xf080L
  val SumWrite                = 0xf081L
  val SumWriteRead            = 0xf082L

  val AdsState = 0xf100L
}
