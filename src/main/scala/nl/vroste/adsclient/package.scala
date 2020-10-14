package nl.vroste

import zio.{ Has, ZIO }
import zio.clock.Clock

/**
 * Minor data types, enums
 *
 * Import nl.vroste.adsclient._ to get access to all data types needed for working with the client
 */
package object adsclient {
  type ErrorCode = Long

  type AdsT[+T] = ZIO[Clock, AdsClientError, T]

  type AdsClient = Has[AdsClient.Service]
}
