package nl.vroste.adsclient

import zio.blocking.Blocking
import zio.clock.Clock
import zio.console.Console
import zio.random.Random
import zio.system.System
import zio.{ DefaultRuntime, Task, ZIO }

import scala.concurrent.Future

trait ZioSupport {
  lazy val runtime: DefaultRuntime = new DefaultRuntime {}
//  implicit def zioToFuture[R <: Any with Clock with Console with System with Random with Blocking, E <: Throwable, A](t: ZIO[R, E, A]): Future[A] = runZioTest[E, A](t)

//  /**
//    * Consumer that collects results in a Seq
//    */
//  def consumerToSeq[T]: Consumer[T, Seq[T]] = Consumer.foldLeft(Seq.empty[T])(_ :+ _)
}
