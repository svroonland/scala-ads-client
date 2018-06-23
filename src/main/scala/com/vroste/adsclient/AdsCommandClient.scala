package com.vroste.adsclient

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

import com.vroste.adsclient.AdsCommand._
import com.vroste.adsclient.AdsResponse._
import monix.eval.{MVar, Task}
import monix.execution.{Cancelable, Scheduler}
import monix.nio.tcp.AsyncSocketChannelClient
import monix.reactive.Observable
import scodec.bits.{BitVector, ByteOrdering, ByteVector}
import scodec.{Attempt, Codec, codecs}

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

    for {
      encodedVarName <- decodeAttemptToTask(codecs.cstring.encode(varName))
      command = AdsWriteReadCommand(0x0000F003, 0x00000000, 4, encodedVarName.toByteVector)
      response <- runCommand[AdsWriteReadCommandResponse](command)
      handle <- Task.fromTry(Try {
        response.data.toLong(signed = false, ordering = ByteOrdering.LittleEndian)
      })
    } yield VariableHandle(handle)
  }

  def releaseVariableHandle(handle: VariableHandle): Task[Unit] = for {
    encodedHandle <- decodeAttemptToTask(codecs.uint32L.encode(handle.value))
//    _ = println("Releaseing variable handle")
    _ <- runCommand[AdsWriteCommandResponse] {
      AdsWriteCommand(0x0000F006, 0x00000000, encodedHandle.toByteVector)
    }
  } yield ()

  def getNotificationHandle(variableHandle: VariableHandle,
                            length: Long,
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
    runCommand[AdsDeleteDeviceNotificationCommandResponse] {
      AdsDeleteDeviceNotificationCommand(notificationHandle.value)
    }.map(_ => ())

  def writeToVariable(variableHandle: VariableHandle, value: ByteVector): Task[Unit] =
    runCommand[AdsWriteCommandResponse] {
      AdsWriteCommand(0x0000F005, variableHandle.value, value)
    }.map(_ => ())

  def readVariable(variableHandle: VariableHandle, size: Long): Task[ByteVector] =
    runCommand[AdsReadCommandResponse] {
      AdsReadCommand(0x0000F005, variableHandle.value, size)
    }.map(_.data)

  def close(): Task[Unit] = {
    for {
      _ <- socketClient.stopReading()
      _ <- socketClient.stopWriting()
      _ <- socketClient.close()
    } yield ()
  }

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
          consumer.apply(Observable.pure(bytes))
        }

      val classTag = implicitly[ClassTag[R]]

      import scala.concurrent.duration._

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
        .timeout(5.seconds) // TODO configurable

      // Execute in parallel to avoid race conditions
      for {
//        _ <- Task.eval(println(s"Running command ${command}"))
        r <- Task.parMap2(writeCommand, receiveResponse) { case (_, response) => response }
        _ <- checkResponse(r)
      } yield r
    }
  }

  def checkResponse(r: AdsResponse): Task[Unit] =
    if (r.errorCode != 0L) {
      Task.raiseError(AdsClientException(s"ADS error 0x${r.errorCode.toHexString}"))
    } else {
      Task.unit
    }

  private val lastInvokeId: AtomicInteger = new AtomicInteger(1)
  private val generateInvokeId: Task[Int] = Task.eval {
    lastInvokeId.getAndIncrement()
  }

  private lazy val tcpObservable: Task[Observable[Array[Byte]]] =
    socketClient.tcpObservable
      .map(_.share)
//      .map(_.doOnTerminate(reason => println(s"Stopping with reason ${reason}")))
      .memoize // Needed to avoid creating the observable more than once

  private lazy val receivedPackets: Observable[AmsPacket] = Observable
    .fromTask(tcpObservable)
    .flatten
    .map(ByteVector.apply)
    .flatMap(bytes => Observable.fromTask(decodeAttemptToTask(Codec.decode[AmsPacket](BitVector(bytes)))).onErrorRecoverWith {
      case ex @ AdsClientException(e) =>
//        println(s"Error decoding packet ${bytes.toHex}: ${e}")
        Observable.raiseError(ex)
    } )
    .map(_.value)
//    .doOnTerminate(e => println(s"Received packets completed. Error: ${e}"))
//    .doOnNext(p => println(s"Received AMS packet type ${p.header.commandId}"))
    .doOnError(e => println(s"Error in receive packets: ${e}"))
    .share

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
          sample <- stamp.samples
        } yield AdsNotificationSampleWithTimestamp(sample.handle, stamp.timestamp, sample.data)
      }
      .flatMap(Observable.fromIterable)
//      .doOnNext(n => println(s"Got notification ${n}"))

  receivedPackets.subscribe()
}

object AdsCommandClient {
  def decodeAttemptToTask[T](attempt: Attempt[T]): Task[T] =
    attempt.fold(cause => Task.raiseError(AdsClientException(cause.messageWithContext)), Task.pure)
}

case class AdsClientException(message: String) extends Exception(message)
