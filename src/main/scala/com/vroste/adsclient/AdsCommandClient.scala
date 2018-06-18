package com.vroste.adsclient

import java.time.temporal.ChronoUnit
import java.time.{Duration, Instant}
import java.util.concurrent.atomic.AtomicInteger

import com.vroste.adsclient.AdsCommand._
import com.vroste.adsclient.AdsResponse._
import com.vroste.adsclient.codec.{DefaultReadables, DefaultWritables}
import monix.eval.Task
import monix.execution.Scheduler
import monix.nio.tcp.AsyncSocketChannelClient
import monix.reactive.Observable
import scodec.Codec
import scodec.bits.{BitVector, ByteOrdering, ByteVector}

import scala.reflect.ClassTag
import scala.util.Try

case class AdsConnectionSettings(amsNetIdTarget: AmsNetId,
                                 amsPortTarget: Int,
                                 amsNetIdSource: AmsNetId,
                                 amsPortSource: Int,
                                 hostname: String,
                                 port: Int = 48898)

case class AdsNotificationSampleWithTimestamp(handle: Long, timestamp: Instant, data: ByteVector)

/**
  * Exposes individual ADS commands as Tasks and all device notifications as an Observable
  *
  * An inner implementation layer of [[AdsClient]]
  *
  * @param scheduler Execution context for reading responses
  */
/* private */ class AdsCommandClient(settings: AdsConnectionSettings, socketClient: AsyncSocketChannelClient)(
  implicit scheduler: Scheduler) {
  import AdsCommandClient._
  def getVariableHandle(varName: String): Task[VariableHandle] = {
    val command = AdsWriteReadCommand(0x0000F003, 0x00000000, DefaultReadables.intReadable.size, asAdsString(varName))

    for {
      response <- runCommand[AdsWriteReadCommandResponse](command)
      handle <- Task.fromTry(Try {
        response.data.toLong(signed = false, ordering = ByteOrdering.LittleEndian)
      })
    } yield VariableHandle(handle)
  }

  def encodeError[T]: T = throw new IllegalArgumentException("Unable to encode")

  def releaseVariableHandle(handle: VariableHandle): Task[Unit] = {
    runCommand[AdsWriteCommandResponse] {
      AdsWriteCommand(0x0000F006, 0x00000000, scodec.codecs.uint32L.encode(handle.value).getOrElse(encodeError).toByteArray)
    }.map(_ => ())
  }

  def getNotificationHandle(variableHandle: VariableHandle,
                            length: Int,
                            maxDelay: Int,
                            cycleTime: Int): Task[NotificationHandle] =
    runCommand[AdsAddDeviceNotificationCommandResponse] {
      AdsAddDeviceNotificationCommand(0x0000F005,
        variableHandle.value,
        length,
        AdsTransmissionMode.OnChange,
        maxDelay,
        cycleTime)
    }.map(_.notificationHandle)
      .map(NotificationHandle)

  def deleteNotificationHandle(notificationHandle: NotificationHandle): Task[Unit] =
    runCommand {
      AdsDeleteDeviceNotificationCommand(notificationHandle.value)
    }.map(_ => ())

  def writeToVariable(variableHandle: VariableHandle, value: Array[Byte]): Task[Unit] =
    runCommand[AdsWriteCommandResponse] {
      AdsWriteCommand(0x0000F005, variableHandle.value, value)
    }.map(_ => ())

  def readVariable(variableHandle: VariableHandle, size: Int): Task[Array[Byte]] =
    runCommand[AdsReadCommandResponse] {
      AdsReadCommand(0x0000F005, variableHandle.value, size)
    }.map(_.data.toArray)

  def close(): Task[Unit] = socketClient.close()

  /**
    * Run a command, await the response to the command and return it
    */
  private def runCommand[R <: AdsResponse : ClassTag](command: AdsCommand): Task[R] = {
    generateInvokeId.flatMap { invokeId =>
      val header = AmsHeader(
        amsNetIdTarget = settings.amsNetIdTarget,
        amsPortTarget = settings.amsPortTarget,
        amsNetIdSource = settings.amsNetIdSource,
        amsPortSource = settings.amsPortSource,
        commandId = AdsCommand.commandId(command),
        stateFlags = 4,
        errorCode = 0,
        invokeId = invokeId,
        data = Left(command)
      )
      val packet = AmsPacket(header)

      val bytes = Codec[AmsPacket].encode(packet).getOrElse(throw new IllegalArgumentException("Unable to encode packet"))
        .toByteArray

      val writeCommand = socketClient.tcpConsumer
        .flatMap { consumer =>
          println(s"Running command ${packet.debugString}")
          consumer.apply(Observable.pure(bytes))
        }.doOnFinish { r => Task.eval(println(s"Done running command with result ${r}")) }

      val classTag = implicitly[ClassTag[R]]

      import scala.concurrent.duration._

      val receiveResponse = receivedPackets
        .filter(_.header.invokeId == invokeId)
        .flatMap(_.header.data match {
          case Right(r) if r.getClass == classTag.runtimeClass =>
            println(s"Got expected response ${r}")
            Observable.pure(r.asInstanceOf[R])
          case r =>
            println("nope, error reading response")
            Observable.raiseError(
              new IllegalArgumentException(s"Expected response for command ${command}, got response $r"))
        })
        .firstL
          .asyncBoundary
          .timeout(5.seconds)

      // Execute in parallel to avoid race conditions. Or can we be sure we don't need this? TODO
      val r = Task.parMap2(writeCommand, receiveResponse) { case (_, response) => response }

      r.flatMap( r => if (r.errorCode != 0L) Task.raiseError(new IllegalArgumentException(s"ADS error ${r.errorCode}")) else Task.pure(r))
    }
  }

  private val lastInvokeId: AtomicInteger = new AtomicInteger(1)
  private val generateInvokeId: Task[Int] = Task.eval {
    lastInvokeId.getAndIncrement()
  }

  private lazy val tcpObservable: Task[Observable[Array[Byte]]] =
    socketClient.tcpObservable
//      .map(_.replay(1).asyncBoundary(OverflowStrategy.DropOld(100))) // Needed to avoid closing when the observable's subscription completes
        .map(_.share)
      .map(_.doOnTerminate(reason => println(s"Stopping with reason ${reason}")))
      .map(_.doOnEarlyStop(() => println(s"TCP Observable STOPPED early")))
      .memoize // Needed to avoid creating the observable more than once

  private lazy val receivedPackets: Observable[AmsPacket] = Observable
    .fromTask(tcpObservable)
    .flatten
    .map(ByteVector.apply)
    .doOnNext(bytes => println(s"Received packet ${bytes.toHex}"))
    .map(bytes => Codec[AmsPacket].asDecoder.decode(BitVector(bytes)))
    .doOnNext(p => println(s"Decoded packet ${p}"))
    .map(_.getOrElse(throw new IllegalArgumentException("Decode error")).value)
    .doOnError(e => println(s"Receive error: ${e}"))
    .share
  // TODO is an Array[Byte] always a complete packet, or a partial packet?

  // Observable of all responses from the ADS server
  private lazy val responses: Observable[AdsResponse] =
    receivedPackets
      .map(_.header.data.toSeq)
      .flatMap(Observable.fromIterable)

  lazy val notificationSamples: Observable[AdsNotificationSampleWithTimestamp] =
    responses
      .collect { case r@AdsNotificationResponse(_) => r }
      .map { r =>
        for {
          stamp <- r.stamps
          timestamp = toInstant(stamp.timestamp)
          sample <- stamp.samples
        } yield AdsNotificationSampleWithTimestamp(sample.handle, timestamp, sample.data)
      }
      .flatMap(Observable.fromIterable)

  lazy val timestampZero: Instant = Instant.parse("1601-01-01T00:00:00Z")

  def toInstant(fileTime: Long): Instant = {
    val duration = Duration.of(fileTime / 10, ChronoUnit.MICROS).plus(fileTime % 10 * 100, ChronoUnit.NANOS)
    timestampZero.plus(duration)
  }

  val receiveSubscription = receivedPackets.subscribe()
}

object AdsCommandClient {
  private[adsclient] def asAdsString(value: String): Array[Byte] = DefaultWritables.stringWritable.toBytes(value)

}
