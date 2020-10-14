//package nl.vroste.adsclient
//
//import zio.stream.Sink
//
//class NotificationsSpec extends BaseSpec {
//  it must "observe a changing variable" in {
//    clientM.use { client =>
//      val var1 = client.notificationsFor("MAIN.var4", int)
//      for {
//        result <- var1.take(3).map(_.value).run(Sink.collectAll[Short])
//        _       = println(s"Result: ${result.mkString(", ")}")
//      } yield result.size mustBe 3
//    }
//  }
//
//  it must "give error when registering notifications for a variable that does not exist" in {
//    clientM.use { client =>
//      for {
//        _ <- client.notificationsFor("MAIN.varNotExist", int).runDrain
//      } yield fail("expected ADSException")
//    }
//  }
//
//  it must "give errors when reading a variable of the wrong type" in {
//    clientM.use { client =>
//      for {
//        _ <- client.read("MAIN.var1", lreal)
//      } yield fail
//    }
//  }
//}
