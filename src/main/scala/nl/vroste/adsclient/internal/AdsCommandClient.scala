package nl.vroste.adsclient.internal

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

import nl.vroste.adsclient._
import nl.vroste.adsclient.internal.AdsCommand._
import nl.vroste.adsclient.internal.AdsResponse._
import nl.vroste.adsclient.internal.AdsSumCommand.{ AdsSumReadCommand, AdsSumWriteCommand, AdsSumWriteReadCommand }
import nl.vroste.adsclient.internal.codecs.{ AdsCommandCodecs, AmsCodecs }
import nl.vroste.adsclient.internal.util.AttemptUtil._
import monix.eval.Task
import monix.execution.Scheduler
import monix.nio.tcp.AsyncSocketChannelClient
import monix.reactive.Observable
import monix.reactive.observables.ConnectableObservable
import scodec.bits.{ BitVector, ByteVector }
import scodec.{ Attempt, Codec }
import shapeless.HList

import scala.reflect.ClassTag

case class AdsNotificationSampleWithTimestamp(handle: Long, timestamp: Instant, data: BitVector)

/**
 * Responsible for encoding and executing single ADS commands and decoding their response
 *
  * Also provides all device notifications as an Observable
 *
  * An inner implementation layer of [[AdsClient]]
 *
  * @param scheduler Execution context for reading responses
 */
class AdsCommandClient(settings: AdsConnectionSettings, socketClient: AsyncSocketChannelClient)(implicit
  scheduler: Scheduler
) extends AmsCodecs {

  import AdsCommandClient._
  import nl.vroste.adsclient.internal.codecs.AdsCommandCodecs.variableHandleCodec

  def getVariableHandle(varName: String): Task[VariableHandle] =
    for {
      command  <- getVariableHandleCommand(varName).toTask
      response <- runCommand[AdsWriteReadCommandResponse](command)
      handle   <- response.decode[VariableHandle].toTask
    } yield handle

  def releaseVariableHandle(handle: VariableHandle): Task[Unit] =
    for {
      command <- releaseVariableHandleCommand(handle).toTask
      _       <- runCommand[AdsWriteCommandResponse](command)
    } yield ()

  def getNotificationHandle(
    variableHandle: VariableHandle,
    length: Long,
    maxDelay: Int,
    cycleTime: Int
  ): Task[NotificationHandle] =
    getNotificationHandle(IndexGroups.ReadWriteSymValByHandle, variableHandle.value, length, maxDelay, cycleTime)

  def getNotificationHandle(
    indexGroup: Long,
    indexOffset: Long,
    length: Long,
    maxDelay: Int,
    cycleTime: Int
  ): Task[NotificationHandle] =
    runCommand[AdsAddDeviceNotificationCommandResponse] {
      AdsAddDeviceNotificationCommand(
        indexGroup,
        indexOffset,
        length,
        AdsTransmissionMode.OnChange,
        maxDelay,
        cycleTime
      )
    }.map(_.notificationHandle)
      .map(NotificationHandle)

  def deleteNotificationHandle(notificationHandle: NotificationHandle): Task[Unit] =
    runCommand[AdsDeleteDeviceNotificationCommandResponse] {
      AdsDeleteDeviceNotificationCommand(notificationHandle.value)
    }.map(_ => ())

  def writeToVariable(variableHandle: VariableHandle, value: BitVector): Task[Unit] =
    runCommand[AdsWriteCommandResponse] {
      AdsWriteCommand(
        indexGroup = IndexGroups.ReadWriteSymValByHandle,
        indexOffset = variableHandle.value,
        values = value
      )
    }.map(_ => ())

  def read(indexGroup: Long, indexOffset: Long, size: Long): Task[BitVector] =
    for {
      command  <- readCommand(indexGroup, indexOffset, size).toTask
      response <- runCommand[AdsReadCommandResponse](command)
    } yield response.data

  def close(): Task[Unit] =
    for {
      _ <- socketClient.stopReading()
      _ <- socketClient.stopWriting()
      _ <- socketClient.close()
    } yield ()

  /**
   * Run a command, await the response to the command and return it
   */
  private[adsclient] def runCommand[R <: AdsResponse: ClassTag](command: AdsCommand): Task[R] =
    for {
      invokeId    <- generateInvokeId
      packet       = createPacket(command, invokeId)
      bytes       <- Codec[AmsPacket].encode(packet).toTask
      consumer    <- socketClient.tcpConsumer
      writeCommand = consumer.apply(Observable.pure(bytes.toByteArray))
      _           <- Task.eval(println(s"Running command ${command}"))
      // Execute in parallel to avoid race conditions
      response    <- Task.parMap2(writeCommand, awaitResponse(invokeId))(keepSecond)
      _           <- checkResponse(response)
    } yield response

  def createPacket(command: AdsCommand, invokeId: Int): AmsPacket =
    AmsPacket(
      AmsHeader(
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
    )

  def awaitResponse[R](invokeId: Int)(implicit classTag: ClassTag[R]): Task[R] =
    receivedPackets
      .filter(_.header.invokeId == invokeId)
      .flatMap(_.header.data match {
        case Right(r) if r.getClass == classTag.runtimeClass =>
          Observable.pure(r.asInstanceOf[R])
        case r                                               =>
          Observable.raiseError(new IllegalArgumentException(s"Unexpected response $r"))
      })
      .firstL
      .timeout(settings.timeout)

  def checkErrorCode(errorCode: Long): Task[Unit] =
    if (errorCode != 0L)
      Task.raiseError(AdsClientException(s"ADS error 0x${errorCode.toHexString}"))
    else
      Task.unit

  def checkErrorCodes(errorCodes: Seq[Long]): Task[Unit] =
    Task.traverse(errorCodes)(checkErrorCode).map(_ => ())

  def checkResponse(r: AdsResponse): Task[Unit] = checkErrorCode(r.errorCode)

  private val lastInvokeId: AtomicInteger = new AtomicInteger(1)
  private val generateInvokeId: Task[Int] = Task.eval {
    lastInvokeId.getAndIncrement()
  }

  private lazy val receivedPackets: ConnectableObservable[AmsPacket] =
    Observable
      .fromTask(socketClient.tcpObservable.memoize)
      .flatten
      .map(BitVector.apply)
      .doOnNext { bits =>
        Task.eval(println(s"Got packet ${bits.toHex}"))
      }
      .flatMap(bits =>
        Observable.fromTask(Codec.decode[AmsPacket](bits).toTask).onErrorRecoverWith {
          case ex @ AdsClientException(_) =>
            Observable.raiseError(ex)
        }
      )
      .map(_.value)
      .publish

  receivedPackets.connect()

  // Observable of all responses from the ADS server
  private lazy val responses: Observable[AdsResponse] =
    receivedPackets
      .map(_.header.data.toSeq)
      .flatMap(Observable.fromIterable)

  lazy val notificationSamples: Observable[AdsNotificationSampleWithTimestamp] =
    responses.collect { case r @ AdsNotificationResponse(_) => r }.map { r =>
      for {
        stamp  <- r.stamps
        sample <- stamp.samples
      } yield AdsNotificationSampleWithTimestamp(sample.handle, stamp.timestamp, sample.data)
    }.flatMap(Observable.fromIterable)
}

object AdsCommandClient extends AdsCommandCodecs {
  def getVariableHandleCommand(varName: String): Attempt[AdsWriteReadCommand] =
    for {
      encodedVarName <- AdsCodecs.string.encode(varName)
    } yield AdsWriteReadCommand(
      indexGroup = IndexGroups.GetSymHandleByName,
      indexOffset = 0x00000000,
      readLength = 4,
      values = encodedVarName
    )

  def releaseVariableHandleCommand(handle: VariableHandle): Attempt[AdsWriteCommand] =
    for {
      encodedHandle <- Codec[VariableHandle].encode(handle)
    } yield AdsWriteCommand(indexGroup = IndexGroups.ReleaseSymHandle, indexOffset = 0x00000000, values = encodedHandle)

  def readVariableCommand(handle: VariableHandle, size: Long): Attempt[AdsReadCommand] =
    readCommand(IndexGroups.ReadWriteSymValByHandle, handle.value, size)

  def readCommand(indexGroup: Long, indexOffset: Long, size: Long): Attempt[AdsReadCommand] =
    Attempt.successful {
      AdsReadCommand(indexGroup, indexOffset, readLength = size)
    }

  def writeVariableCommand(handle: VariableHandle, value: BitVector): Attempt[AdsWriteCommand] =
    Attempt.successful {
      AdsWriteCommand(IndexGroups.ReadWriteSymValByHandle, indexOffset = handle.value, values = value)
    }

  def createVariableHandlesCommand(variables: Seq[String]): Attempt[AdsSumWriteReadCommand] =
    for {
      encodedVarNames <- variables.map(AdsCodecs.string.encode).sequence
      commands         = encodedVarNames.map(
                   AdsWriteReadCommand(IndexGroups.GetSymHandleByName, indexOffset = 0x00000000, readLength = 4, _)
                 )
    } yield AdsSumWriteReadCommand(commands)

  def readVariablesCommand(handlesAndLengths: Seq[(VariableHandle, Long)]): Attempt[AdsSumReadCommand] =
    for {
      subCommands <- handlesAndLengths.map { case (handle, sizeInBits) => (handle, sizeInBits / 8) }
                       .map((readVariableCommand _).tupled)
                       .sequence
    } yield AdsSumReadCommand(subCommands)

  def writeVariablesCommand[T <: HList](
    handlesAndLengths: Seq[(VariableHandle, Long)],
    codec: Codec[T],
    value: T
  ): Attempt[AdsSumWriteCommand] = {
    val (handles, lengthsInBits) = handlesAndLengths.unzip
    for {
      encodedValue <- codec.encode(value)
      encodedValues = splitBitVectorAtPositions(encodedValue, lengthsInBits.toList)

      subCommands <- handles.zip(encodedValues).map((writeVariableCommand _).tupled).sequence
    } yield AdsSumWriteCommand(subCommands, encodedValue)
  }

  def releaseHandlesCommand(handles: Seq[VariableHandle]): Attempt[AdsSumWriteCommand] =
    for {
      subCommands <- handles.map(releaseVariableHandleCommand).sequence
      values       = BitVector.concat(subCommands.map(_.values))
    } yield AdsSumWriteCommand(subCommands, values)

  def splitBitVectorAtPositions(bitVector: BitVector, lengthsInBits: List[Long]): List[BitVector] =
    lengthsInBits
      .foldLeft((bitVector, List.empty[BitVector])) {
        case ((remaining, acc), length) =>
          val (value, newRemaining) = remaining.splitAt(length)
          (newRemaining, acc :+ value)
      }
      ._2

  def keepSecond[T, U](first: T, second: U): U = second
}
