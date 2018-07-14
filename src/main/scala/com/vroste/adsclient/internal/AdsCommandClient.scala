package com.vroste.adsclient.internal

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

import com.vroste.adsclient._
import com.vroste.adsclient.internal.AdsCommand._
import com.vroste.adsclient.internal.AdsResponse._
import com.vroste.adsclient.internal.AdsSumCommand.{AdsSumReadCommand, AdsSumWriteCommand, AdsSumWriteReadCommand}
import com.vroste.adsclient.internal.codecs.AmsCodecs
import com.vroste.adsclient.internal.util.AttemptUtil._
import monix.eval.Task
import monix.execution.Scheduler
import monix.nio.tcp.AsyncSocketChannelClient
import monix.reactive.Observable
import monix.reactive.observables.ConnectableObservable
import scodec.bits.{BitVector, ByteVector}
import scodec.{Attempt, Codec}
import shapeless.HList

import scala.annotation.tailrec
import scala.reflect.ClassTag

case class AdsNotificationSampleWithTimestamp(handle: Long, timestamp: Instant, data: ByteVector)

/**
  * Exposes individual ADS commands as Tasks and all device notifications as an Observable
  *
  * An inner implementation layer of [[AdsClient]]
  *
  * @param scheduler Execution context for reading responses
  */
class AdsCommandClient(settings: AdsConnectionSettings, socketClient: AsyncSocketChannelClient)(
  implicit scheduler: Scheduler) extends AmsCodecs {

  import AdsCommandClient._

  def getVariableHandle(varName: String): Task[VariableHandle] =
    for {
      command <- getVariableHandleCommand(varName).toTask
      response <- runCommand[AdsWriteReadCommandResponse](command)
      handle <- response.decode[VariableHandle].toTask
    } yield handle

  def releaseVariableHandle(handle: VariableHandle): Task[Unit] =
    for {
      command <- releaseVariableHandleCommand(handle).toTask
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
    for {
      command <- readVariableCommand(variableHandle, size).toTask
      response <- runCommand[AdsReadCommandResponse](command)
    } yield response.data

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
  def getVariableHandleCommand(varName: String): Attempt[AdsWriteReadCommand] =
    for {
      encodedVarName <- AdsCodecs.string.encode(varName)
    } yield AdsWriteReadCommand(indexGroup = 0x0000F003, indexOffset = 0x00000000, readLength = 4, values = encodedVarName.toByteVector)

  def releaseVariableHandleCommand(handle: VariableHandle): Attempt[AdsWriteCommand] =
    for {
      encodedHandle <- Codec[VariableHandle].encode(handle)
    } yield AdsWriteCommand(indexGroup = 0x0000F006, indexOffset = 0x00000000, values = encodedHandle.toByteVector)

  def readVariableCommand(handle: VariableHandle, size: Long): Attempt[AdsReadCommand] =
    Attempt.successful {
      AdsReadCommand(indexGroup = 0x0000F005, indexOffset = handle.value, readLength = size)
    }

  def writeVariableCommand(handle: VariableHandle, value: ByteVector): Attempt[AdsWriteCommand] =
    Attempt.successful {
      AdsWriteCommand(indexGroup = 0x0000F005, indexOffset = handle.value, values = value)
    }

  def createVariableHandlesCommand(variables: Seq[String]): Attempt[AdsSumWriteReadCommand] =
    for {
      encodedVarNames <- variables.map(AdsCodecs.string.encode).sequence
      commands = encodedVarNames.map(_.toByteVector).map(AdsWriteReadCommand(0x0000F003, 0x00000000, 4, _))
    } yield AdsSumWriteReadCommand(commands)

  def readVariablesCommand(handlesAndLengths: Seq[(VariableHandle, Long)]): Attempt[AdsSumReadCommand] =
    for {
      subCommands <- handlesAndLengths.map((readVariableCommand _).tupled).sequence
    } yield AdsSumReadCommand(subCommands)

  def writeVariablesCommand[T <: HList](handlesAndLengths: Seq[(VariableHandle, Long)], codec: Codec[T], value: T): Attempt[AdsSumWriteCommand] = {
    val handles = handlesAndLengths.map(_._1)
    val lengths = handlesAndLengths.map(_._2)
    for {
      encodedValue <- codec.encode(value).map(_.toByteVector)
      encodedValues = splitByteVectorAtPositions(encodedValue, lengths.toList)

      subCommands <- handles.zip(encodedValues).map((writeVariableCommand _).tupled).sequence
    } yield AdsSumWriteCommand(subCommands, encodedValue)
  }

  def releaseHandlesCommand(handles: Seq[VariableHandle]): Attempt[AdsSumWriteCommand] =
    for {
      subCommands <- handles.map(releaseVariableHandleCommand).sequence
      values = ByteVector.concat(subCommands.map(_.values))
    } yield AdsSumWriteCommand(subCommands, values)

  // TODO does this work for strings..?
  @tailrec
  def splitByteVectorAtPositions(remaining: ByteVector, lengths: List[Long], acc: List[ByteVector] = List.empty): List[ByteVector] = {
    import scala.collection.immutable.::
    lengths match {
      case Nil => acc
      case l :: ls =>
        val (value, newRemaining) = remaining.splitAt(l)
        splitByteVectorAtPositions(newRemaining, ls, acc :+ value)
    }
  }

  def keepSecond[T, U](first: T, second: U): U = second
}

case class AdsClientException(message: String) extends Exception(message)
