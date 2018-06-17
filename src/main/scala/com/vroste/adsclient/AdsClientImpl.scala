package com.vroste.adsclient

import com.vroste.adsclient.codec.{AdsReadable, AdsWritable}
import monix.eval.Task
import monix.reactive.{Consumer, Observable}

class AdsClientImpl(client: AdsCommandClient) extends AdsClient {

  override def read[T: AdsReadable](varName: String): Task[T] = {
    val readable = implicitly[AdsReadable[T]]
    for {
      _ <- Task.eval(println("Getting variable handle"))
      varHandle <- client.getVariableHandle(varName)
      _ <- Task.eval(println(s"Got variable handle ${varHandle}"))
      data <- client
        .readVariable(varHandle, readable.size)
        .doOnFinish { _ =>
          client.releaseVariableHandle(varHandle)
        }
    } yield readable.fromBytes(data)
  }

  override def write[T: AdsWritable](varName: String, value: T): Task[Unit] = {
    val writable = implicitly[AdsWritable[T]]
    for {
      varHandle <- client.getVariableHandle(varName)
      _ <- client
        .writeToVariable(varHandle, writable.toBytes(value))
        .doOnFinish { _ =>
          client.releaseVariableHandle(varHandle)
        }
    } yield ()
  }

  /**
    * Creates an observable that emits whenever an ADS notification is received
    *
    * A symbol handle and device notification are created and cleaned up when the observable terminates.
    *
    * @param varName PLC variable name
    * @tparam T Type of the value. An implicit [[AdsReadable]] for this type must be in scope
    * @return
    */
  override def notificationsFor[T: AdsReadable](varName: String): Observable[AdsNotification[T]] = {
    val readable = implicitly[AdsReadable[T]]

    // Ensures that created variable and notification handles are cleaned up
    def usingNotificationHandle[U](f: NotificationHandle => Observable[U]): Observable[U] =
      Observable.fromTask {
        for {
          varHandle          <- client.getVariableHandle(varName)
          notificationHandle <- client.getNotificationHandle(varHandle, readable.size, 0, 0) // TODO cycletime
        } yield
          f(notificationHandle)
            .doOnTerminateTask(_ => client.deleteNotificationHandle(notificationHandle))
            .doOnTerminateTask(_ => client.releaseVariableHandle(varHandle))
      }.flatten

    usingNotificationHandle { handle =>
      client.notificationSamples
        .filter(_.handle == handle.value)
        .map { sample =>
          val data = readable.fromBytes(sample.data)
          AdsNotification(data, sample.timestamp)
        }
    }
  }

  /**
    * Creates a consumer that writes elements to a PLC variable
    *
    * A symbol handle is created when the first value is consumed and cleaned up when
    * there are no more values to consume.
    *
    * @param varName PLC variable name
    * @tparam T Type of the value. An implicit [[AdsWritable]] for this type must be in scope
    * @return
    */
  override def consumerFor[T: AdsWritable](varName: String): Consumer[T, Unit] =
    consumerBracket[T, VariableHandle](client.getVariableHandle(varName), client.releaseVariableHandle) {
      Consumer.foreachTask {
        case (handle, value) => client.writeToVariable(handle, implicitly[AdsWritable[T]].toBytes(value))
      }
    }

  /**
    * Creates a consumer that creates a resource before handling the first element and cleans it up after
    * handling the last element
    *
    * @param acquire Create a resource asychronously
    * @param release Cleanup the resource after the last
    * @param inner Consumer for tuples of the resource and a value
    * @tparam T Type of elements to consume
    * @tparam R Type of the resource
    * @return A consumer of elements of type T, when executed will result in a value of type Unit
    */
  def consumerBracket[T, R](acquire: Task[R], release: R => Task[Unit])(
      inner: Consumer[(R, T), Unit]): Consumer[T, Unit] = {
    val resourceT = acquire.memoize

    inner
      .transformInput[T] {
        _.mapTask(t => resourceT.map((_, t)))
      }
      .mapTask { _ =>
        resourceT.flatMap(release)
      }
  }

  override def close(): Task[Unit] = client.close()
}
