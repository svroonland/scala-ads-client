package nl.vroste.adsclient.internal

import monix.eval.{ MVar, Task }

/**
 * Thread-safe way of tracking a number of resources
 */
class CountingSemaphore {
  private val lockedT: Task[MVar[Unit]] = MVar(()).memoize // A full MVar means free, empty MVar means locked
  private val countT: Task[MVar[Int]]   = MVar(0).memoize
  private val zeroT: Task[MVar[Unit]]   = MVar(()).memoize

  /**
   * Increment the count atomically
   *
   * @return
   */
  def increment: Task[Unit] =
    withLock {
      for {
        count <- countT
        zero  <- zeroT
        i     <- count.take
        _     <- if (i == 0) zero.take else Task.unit
//      _ <- Task.eval(println(s"Incrementing semaphore to ${i + 1}"))
        _     <- count.put(i + 1)
      } yield ()
    }

  /**
   * Decrement the count atomically
   *
   * @return
   */
  def decrement: Task[Unit] =
    withLock {
      for {
        count <- countT
        zero  <- zeroT
        i     <- count.take
        _     <- if (i == 1) zero.put(()) else Task.unit
//      _ <- Task.eval(println(s"Drecrementing semaphore to ${i - 1}"))
        _     <- count.put(i - 1)
      } yield ()
    }

  /**
   * Task that completes when the count is zero
   */
  def awaitZero: Task[Unit] = // withLock {
    for {
//      _ <- Task.eval(println("Awaiting zero"))
      zero <- zeroT
      _    <- zero.read
//      _ <- Task.eval(println("Semaphore is zero"))
    } yield ()
  // }

  private def withLock[T](f: => Task[T]): Task[T] =
    for {
      locked <- lockedT
      _      <- locked.take
      r      <- f.doOnCancel(locked.put(()))
      _      <- locked.put(())
    } yield r
}
