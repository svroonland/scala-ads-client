package nl.vroste.adsclient

import monix.eval.Task

class ConnectSpec extends BaseSpec {
  "ADSClient" must "connect and close to a PLC" in {
    withClient { _ =>
      Task.pure(succeed)
    }
  }
}
