package nl.vroste.adsclient.internal.util

import scodec.{ Attempt, Err }
import zio.{ IO, Task, ZIO }

object AttemptUtil {

  implicit class AttemptToTask[+T](val attempt: Attempt[T]) extends AnyVal {
    def toTask[R, E](e: Err => E): ZIO[R, E, T] =
      attempt.fold(cause => IO.fail(e(cause)), Task.succeed(_))
  }

  implicit class AttemptSequence[T](val seq: Seq[Attempt[T]]) extends AnyVal {

    /**
     * Turn a Seq[Attempt[T]] into an Attempt[Seq[T]]
     *
     * If any of the attempts have failed, the result will be a failed attempt
     * @return
     */
    def sequence: Attempt[Seq[T]] =
      seq.foldLeft(Attempt.successful(Seq.empty[T])) {
        case (as, a) => as.flatMap(s => a.map(s :+ _))
      }
  }
}
