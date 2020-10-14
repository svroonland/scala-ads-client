package nl.vroste.adsclient

import monix.eval.Task
import monix.reactive.Consumer

import scala.concurrent.Future

trait MonixSupport {
  implicit val scheduler = monix.execution.Scheduler.Implicits.global

  implicit def taskToFuture[T](t: Task[T]): Future[T] = t.runToFuture

  /**
   * Consumer that collects results in a Seq
   */
  def consumerToSeq[T]: Consumer[T, Seq[T]] = Consumer.foldLeft(Seq.empty[T])(_ :+ _)
}
