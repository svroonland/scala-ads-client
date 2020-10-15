package nl.vroste.adsclient.internal
import java.util.concurrent.TimeoutException

import zio.{ Chunk, Queue, Schedule, ZIO, ZManaged }
import zio.clock.Clock
import zio.duration.Duration
import zio.nio.channels.AsynchronousSocketChannel
import zio.nio.core.SocketAddress
import zio.stream.ZStream

object SocketClient {
  val maxFrameSize   = 1024
  val writeQueueSize = 10

  def make(
    hostName: String,
    port: Int,
    timeout: Duration
  ): ZManaged[Clock, Exception, (ZStream[Clock, Exception, Byte], Queue[Chunk[Byte]])] = for {
    channel     <- AsynchronousSocketChannel()
    inetAddress <- SocketAddress.inetSocketAddress(hostName, port).toManaged_
    _           <- channel
                     .connect(inetAddress)
                     .timeoutFail(new TimeoutException("Timeout connecting to socket server"))(timeout)
                     .toManaged_
    writeQueue  <- Queue.bounded[Chunk[Byte]](writeQueueSize).toManaged(_.shutdown)
    inputStream  = createInputStream(channel)
    _           <- writeLoop(channel, writeQueue).forkManaged
  } yield (inputStream, writeQueue)

  private def createInputStream(channel: AsynchronousSocketChannel): ZStream[Clock, Exception, Byte] =
    ZStream.repeatEffectChunk(channel.readChunk(maxFrameSize).retry(Schedule.forever))

  private def writeLoop(channel: AsynchronousSocketChannel, queue: Queue[Chunk[Byte]]): ZIO[Any, Exception, Unit] =
    ZStream.fromQueue(queue).mapM(channel.writeChunk).runDrain
}
