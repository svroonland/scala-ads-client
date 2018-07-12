package com.vroste.adsclient

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

import com.vroste.adsclient.AdsCommand._
import com.vroste.adsclient.AdsResponse._
import com.vroste.adsclient.codec.AdsCodecs
import monix.eval.Task
import monix.execution.Scheduler
import monix.nio.tcp.AsyncSocketChannelClient
import monix.reactive.Observable
import monix.reactive.observables.ConnectableObservable
import scodec.bits.{BitVector, ByteVector}
import scodec.{Attempt, Codec}

import scala.reflect.ClassTag

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

  private val handleCodec = AdsCodecs.udint

  def getVariableHandle(varName: String): Task[VariableHandle] =
    for {
      encodedVarName <- attemptToTask(AdsCodecs.string.encode(varName))
      command = AdsWriteReadCommand(
        indexGroup = 0x0000F003,
        indexOffset = 0x00000000,
        readLength = 4,
        values = encodedVarName.toByteVector
      )
      response <- runCommand[AdsWriteReadCommandResponse](command)
      handle <- attemptToTask(handleCodec.decodeValue(response.data.toBitVector))
    } yield VariableHandle(handle)

  def releaseVariableHandle(handle: VariableHandle): Task[Unit] =
    for {
      encodedHandle <- attemptToTask(handleCodec.encode(handle.value))
      command = AdsWriteCommand(
        indexGroup = 0x0000F006,
        indexOffset = 0x00000000,
        values = encodedHandle.toByteVector
      )
      _ <- runCommand[AdsWriteCommandResponse](command)
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
      AdsWriteCommand(indexGroup = 0x0000F005, indexOffset = variableHandle.value, values = value)
    }.map(_ => ())

  def readVariable(variableHandle: VariableHandle, size: Long): Task[ByteVector] =
    runCommand[AdsReadCommandResponse] {
      AdsReadCommand(indexGroup = 0x0000F005, indexOffset = variableHandle.value, readLength = size)
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
  private[adsclient] def runCommand[R <: AdsResponse : ClassTag](command: AdsCommand): Task[R] = for {
    invokeId <- generateInvokeId
    header = AmsHeader(
      amsNetIdTarget = settings.amsNetIdTarget,
      amsPortTarget = settings.amsPortTarget,
      amsNetIdSource = settings.amsNetIdSource,
      amsPortSource = settings.amsPortSource,
      commandId = AdsCommand.commandId(command),
      stateFlags = 4, // ADS command
      errorCode = 0,
      invokeId = invokeId,
      data = Left(command)
    )
    packet = AmsPacket(header)
    bytes <- attemptToTask(Codec[AmsPacket].encode(packet))

    consumer <- socketClient.tcpConsumer
    writeCommand = consumer.apply(Observable.pure(bytes.toByteArray))

    classTag = implicitly[ClassTag[R]]

    receiveResponse = receivedPackets
      .filter(_.header.invokeId == invokeId)
      .flatMap(_.header.data match {
        case Right(r) if r.getClass == classTag.runtimeClass =>
          Observable.pure(r.asInstanceOf[R])
        case r =>
          Observable.raiseError(
            new IllegalArgumentException(s"Expected response for command ${command}, got response $r"))
      })
      .firstL
      .timeout(settings.timeout)

    // Execute in parallel to avoid race conditions
    _ <- Task.eval(println(s"Running command ${command}"))
    response <- Task.parMap2(writeCommand, receiveResponse) { case (_, response) => response }
    _ <- checkResponse(response)
  } yield response

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

  private lazy val receivedPackets: ConnectableObservable[AmsPacket] =
    Observable
      .fromTask(socketClient.tcpObservable.memoize)
      .flatten
      .map(ByteVector.apply)
      .flatMap(bytes => Observable.fromTask(attemptToTask(Codec.decode[AmsPacket](BitVector(bytes)))).onErrorRecoverWith {
        case ex@AdsClientException(_) =>
          Observable.raiseError(ex)
      })
      .map(_.value)
      .doOnNext(p => println(s"Received AMS packet type ${p.header.commandId}"))
      .doOnError(e => println(s"Error in receive packets: ${e}"))
      .publish

  receivedPackets.connect()

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
}

object AdsCommandClient {
  def attemptToTask[T](attempt: Attempt[T]): Task[T] =
    attempt.fold(cause => Task.raiseError(AdsClientException(cause.messageWithContext)), Task.pure)

  def attemptSeq[T](attempts: Seq[Attempt[T]]): Attempt[Seq[T]] =
    attempts.foldLeft(Attempt.successful(Seq.emptyT])) {
      case (as, a) => as.flatMap(s => a.map(s :+ _))
    }
}

case class AdsClientException(message: String) extends Exception(message)
