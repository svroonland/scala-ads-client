package com.vroste.adsclient

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

import com.vroste.adsclient.AdsCommand._
import com.vroste.adsclient.AdsResponse._
import com.vroste.adsclient.AttemptConversion._
import com.vroste.adsclient.codec.AdsCodecs
import monix.eval.Task
import monix.execution.Scheduler
import monix.nio.tcp.AsyncSocketChannelClient
import monix.reactive.Observable
import monix.reactive.observables.ConnectableObservable
import scodec.Codec
import scodec.bits.{BitVector, ByteVector}

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

  private val handleCodec = AdsCodecs.udint

  def getVariableHandle(varName: String): Task[VariableHandle] =
    for {
      encodedVarName <- AdsCodecs.string.encode(varName).toTask
      command = AdsWriteReadCommand(
        indexGroup = 0x0000F003,
        indexOffset = 0x00000000,
        readLength = 4,
        values = encodedVarName.toByteVector
      )
      response <- runCommand[AdsWriteReadCommandResponse](command)
      handle <- handleCodec.decodeValue(response.data.toBitVector).toTask
    } yield VariableHandle(handle)

  def releaseVariableHandle(handle: VariableHandle): Task[Unit] =
    for {
      encodedHandle <- handleCodec.encode(handle.value).toTask
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
    packet = createPacket(command, invokeId)
    bytes <- Codec[AmsPacket].encode(packet).toTask
    consumer <- socketClient.tcpConsumer
    writeCommand = consumer.apply(Observable.pure(bytes.toByteArray))
    _ <- Task.eval(println(s"Running command ${command}"))
    // Execute in parallel to avoid race conditions
    response <- Task.parMap2(writeCommand, awaitResponse(invokeId))(keepSecond)
    _ <- checkResponse(response)
  } yield response

  def createPacket(command: AdsCommand, invokeId: Int): AmsPacket =
    AmsPacket(AmsHeader(
      amsNetIdTarget = settings.amsNetIdTarget,
      amsPortTarget = settings.amsPortTarget,
      amsNetIdSource = settings.amsNetIdSource,
      amsPortSource = settings.amsPortSource,
      commandId = AdsCommand.commandId(command),
      stateFlags = 4, // ADS command
      errorCode = 0,
      invokeId = invokeId,
      data = Left(command)
    ))

  def awaitResponse[R](invokeId: Int)(implicit classTag: ClassTag[R]): Task[R] =
    receivedPackets
      .filter(_.header.invokeId == invokeId)
      .flatMap(_.header.data match {
        case Right(r) if r.getClass == classTag.runtimeClass =>
          Observable.pure(r.asInstanceOf[R])
        case r =>
          Observable.raiseError(
            new IllegalArgumentException(s"Unexpected response $r"))
      })
      .firstL
      .timeout(settings.timeout)

  def checkResponse(r: AdsResponse): Task[Unit] =
    if (r.errorCode != 0L) {
      Task.raiseError(AdsClientException(s"ADS error 0x${r.errorCode.toHexString}"))
    } else {
      Task.unit
    }

  def keepSecond[T, U](first: T, second: U): U = second

  private val lastInvokeId: AtomicInteger = new AtomicInteger(1)
  private val generateInvokeId: Task[Int] = Task.eval {
    lastInvokeId.getAndIncrement()
  }

  private lazy val receivedPackets: ConnectableObservable[AmsPacket] =
    Observable
      .fromTask(socketClient.tcpObservable.memoize)
      .flatten
      .map(ByteVector.apply)
      .flatMap(bytes => Observable.fromTask(Codec.decode[AmsPacket](BitVector(bytes)).toTask).onErrorRecoverWith {
        case ex@AdsClientException(_) =>
          Observable.raiseError(ex)
      })
      .map(_.value)
      .doOnNext(p => println(s"Received AMS packet type ${p.header.commandId}"))
      .doOnError(e => println(s"Error in receive packets: ${e}"))
      .doOnTerminate(_.foreach { ex =>
        println(s"Received packets completed with exception ${ex.getMessage}")
        ex.printStackTrace()
      })
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

case class AdsClientException(message: String) extends Exception(message)
