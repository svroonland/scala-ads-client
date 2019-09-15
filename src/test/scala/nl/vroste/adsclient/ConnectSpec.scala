package nl.vroste.adsclient

import zio.Task

class ConnectSpec extends BaseSpec {
  "ADSClient" must "connect and close to a PLC" in {
    clientM.use { _ =>
      Task.succeed(succeed)
    }
  }
}
