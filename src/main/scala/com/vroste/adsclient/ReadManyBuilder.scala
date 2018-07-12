package com.vroste.adsclient

import com.vroste.adsclient.AdsCommand.{AdsSumReadCommand, AdsSumWriteReadCommand, AdsWriteReadCommand}
import com.vroste.adsclient.AdsResponse.AdsWriteReadCommandResponse
import com.vroste.adsclient.codec.AdsCodecs
import monix.eval.Task
import scodec.Codec
import shapeless.{::, HList}

trait ReadManyBuilder[T <: HList] {
  def and[U](varName: String, codec: Codec[U]): ReadManyBuilder[U :: T]

  def read: Task[T]
}

import com.vroste.adsclient.AdsCommandClient.{attemptSeq, attemptToTask}

class ReadManyBuilderImpl[T <: HList](variables: Seq[String], sizes: Seq[Long], codec: Codec[T], client: AdsCommandClient) extends ReadManyBuilder[T] {

  import ReadManyBuilderImpl._

  override def and[U](varName: String, codecU: Codec[U]): ReadManyBuilder[U :: T] =
    new ReadManyBuilderImpl[U :: T](variables :+ varName, sizes :+ codec.sizeBound.exact.get, codecU :: codec, client)

  override def read: Task[T] = {
    for {
      handles <- createHandles
      values <- readValues(handles)
        .doOnFinish(_ => releaseHandles(handles))
    } yield values
  }

  def createHandles: Task[Seq[VariableHandle]] =
    for {
      encodedVarNames <- attemptToTask(attemptSeq(variables.map(AdsCodecs.string.encode)))
      command = AdsSumWriteReadCommand(encodedVarNames.map(_.toByteVector).map(AdsWriteReadCommand(0x0000F003, 0x00000000, 4, _)))
      response <- client.runCommand[AdsWriteReadCommandResponse](command)

      handles <- attemptToTask(scodec.codecs.list(handleCodec).decodeValue(response.data.toBitVector))
    } yield handles.map(VariableHandle)

  def releaseHandles(handles: Seq[VariableHandle]): Task[Unit]

  def readValues(handles: Seq[VariableHandle]): Task[T] =
    for {
      encodedVarNames <- attemptToTask(attemptSeq(variables.map(AdsCodecs.string.encode)))
      command = AdsSumReadCommand(encodedVarNames.map(_.toByteVector).map(AdsWriteReadCommand(0x0000F003, 0x00000000, 4, _)))
      response <- client.runCommand[AdsWriteReadCommandResponse](command)

      handles <- attemptToTask(scodec.codecs.list(handleCodec).decodeValue(response.data.toBitVector))
    } yield handles.map(VariableHandle)
}

object ReadManyBuilderImpl {
  private val handleCodec = AdsCodecs.udint
}

case class VariableInfo()
