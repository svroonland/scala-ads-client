package nl.vroste.adsclient

class NotificationsSpec extends BaseSpec {
  it must "observe a changing variable" in {
    val var1   = client.notificationsFor("MAIN.var4", int)
    val result = for {
      result <- var1.take(3).map(_.value).consumeWith(consumerToSeq).executeAsync
      _       = println(s"Result: ${result.mkString(", ")}")
    } yield result.size mustBe 3

    result
  }

  it must "give error when registering notifications for a variable that does not exist" in {
    val result = for {
      _ <- client.notificationsFor("MAIN.varNotExist", int).firstOptionL
    } yield fail("expected ADSException")
    result.onErrorRecover { case AdsClientException(_) => succeed }
  }

  it must "give errors when reading a variable of the wrong type" in {
    val result = for {
      _ <- client.read("MAIN.var1", lreal)
    } yield fail
    result.onErrorRecover { case AdsClientException(_) => succeed }
  }
}
