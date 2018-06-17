package com.vroste.adsclient

import java.time.{Duration, Instant}
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicInteger

import AdsResponse._
import com.vroste.adsclient.AdsCommand._
import com.vroste.adsclient.codec.{DefaultReadables, DefaultWritables}
import monix.eval.Task
import monix.execution.Scheduler
import monix.nio.tcp.AsyncSocketChannelClient
import monix.reactive.Observable
import scodec.Codec
import scodec.bits.{BitVector, ByteVector}

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
      response <- runCommand[AdsWriteReadCommand, AdsWriteReadCommandResponse](command)
      handle <- Task.fromTry(Try {
        response.data.toInt(signed = false)
      })
    } yield VariableHandle(handle)
  }

  def releaseVariableHandle(handle: VariableHandle): Task[Unit] = {
    runCommand[AdsWriteCommand, AdsWriteCommandResponse] {
      AdsWriteCommand(0x0000F006, 0x00000000, Array(handle.value.toByte))
    }.map(_ => ())
  }

  def getNotificationHandle(variableHandle: VariableHandle,
                            length: Int,
                            maxDelay: Int,
                            cycleTime: Int): Task[NotificationHandle] =
    runCommand[AdsAddDeviceNotificationCommand, AdsAddDeviceNotificationCommandResponse] {
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
    runCommand[AdsWriteCommand, AdsWriteCommandResponse] {
      AdsWriteCommand(0x0000F005, variableHandle.value, value)
    }.map(_ => ())

  def readVariable(variableHandle: VariableHandle, size: Int): Task[Array[Byte]] =
    runCommand[AdsReadCommand, AdsReadCommandResponse] {
      AdsReadCommand(0x0000F005, variableHandle.value, size)
    }.map(_.data.toArray)

  def close(): Task[Unit] = socketClient.close()

  /**
    * Run a command, await the response to the command and return it
    */
  private def runCommand[T <: AdsCommand, R <: AdsResponse : ClassTag](command: T): Task[R] = {
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

      val receiveResponse = receivedPackets
        .filter(_.header.invokeId == invokeId)
        .flatMap(_.header.data match {
          case Right(r) if r.getClass == classTag.runtimeClass =>
            Observable.pure(r.asInstanceOf[R])
          case r =>
            Observable.raiseError(
              new IllegalArgumentException(s"Expected response for command ${command}, got response $r"))
        })
        .firstL

      // Execute in parallel to avoid race conditions. Or can we be sure we don't need this? TODO
      Task.parMap2(writeCommand, receiveResponse) { case (_, response) => response }
    }
  }

  private val lastInvokeId: AtomicInteger = new AtomicInteger(1)
  private val generateInvokeId: Task[Int] = Task.eval {
    lastInvokeId.getAndIncrement()
  }

  private lazy val tcpObservable: Task[Observable[Array[Byte]]] =
    socketClient.tcpObservable
      .map(_.share) // Needed to avoid closing when the observable's subscription completes
      .map(_.doOnTerminate(reason => s"Stopping with reason ${reason}"))
      .memoize // Needed to avoid creating the observable more than once

  private val receivedPackets: Observable[AmsPacket] = Observable
    .fromTask(tcpObservable)
    .flatten
    .doOnError(e => s"Receive erro ${e}")
    .doOnNext(bytes => println(s"Got packet ${bytes}"))
    .map(bytes => Codec[AmsPacket].asDecoder.decode(BitVector(bytes)).getOrElse(throw new IllegalArgumentException("Decode error")).value)
  // TODO is an Array[Byte] always a complete packet, or a partial packet?

  // Observable of all responses from the ADS server
  private lazy val responses: Observable[AdsResponse] =
    receivedPackets
      .map(_.header.data.toSeq)
      .flatMap(Observable.fromIterable)

  val notificationSamples: Observable[AdsNotificationSampleWithTimestamp] =
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
}

object AdsCommandClient {
  private[adsclient] def asAdsString(value: String): Array[Byte] = DefaultWritables.stringWritable.toBytes(value)

}
