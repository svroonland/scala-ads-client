package nl.vroste.adsclient

import org.scalatest.{ Assertion, AsyncFlatSpec, MustMatchers }
import zio.{ DefaultRuntime, ZIO }

import scala.concurrent.Future

trait BaseSpec extends AsyncFlatSpec with MustMatchers with WithAdsClient with ZioSupport {

  implicit def runZioTest[R >: DefaultRuntime#Environment, E](z: ZIO[R, E, Assertion]): Future[Assertion] =
    runtime.unsafeRunToFuture(
      z.fold(e => fail(e.toString), identity)
    )

//  implicit def failOnZioError[R, E](z: ZIO[R, E, Assertion]): ZIO[R, Throwable, Assertion] =
//    z.fold(e => fail(e.toString), identity)

//  implicit def zioTestResultToFuture[R >: DefaultRuntime#Environment, E <: Throwable](z: ZIO[R, E, Assertion]) =
//    runZioTest(failOnZioError(z))

}
