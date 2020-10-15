package nl.vroste.adsclient.internal
import scodec.Attempt.Successful
import scodec.bits.BitVector
import scodec.{ Attempt, Codec, DecodeResult, Err }
import zio.stream.ZStream
import zio.{ Chunk, ZIO }

object ZStreamScodecOps {

  /**
   * Decode a stream of S from a byte stream using the Codec for S
   * @param stream
   * @param decodingError How to encode
   * @tparam R
   * @tparam E
   * @tparam E1
   * @tparam S
   * @return
   */
  def decodeStream[R, E, E1 >: E, S: Codec](
    stream: ZStream[R, E, Byte],
    decodingError: Err => E1
  ): ZStream[R, E1, S] =
    stream
      .mapChunks(Chunk.single)
      .map(BitVector(_))
      //      .tap(bits => ZIO.effectTotal(println(s"Got packet ${bits.toHex}")))
      .mapAccumM[R, E1, BitVector, Seq[S]](BitVector.empty) { (acc, curr) =>
        val availableBits = acc ++ curr
        if (availableBits.size >= implicitly[Codec[S]].sizeBound.lowerBound)
          Codec.decodeCollect[Seq, S](implicitly[Codec[S]], None)(availableBits) match {
            case Successful(DecodeResult(frames, remainder)) =>
              ZIO.succeed((remainder, frames))
            case Attempt.Failure(_: Err.InsufficientBits)    =>
              ZIO.succeed((availableBits, Seq.empty[S]))
            case Attempt.Failure(cause)                      =>
              ZIO.fail(decodingError(cause))
          }
        else
          ZIO.succeed((availableBits, Seq.empty[S]))
      }
      .mapConcat(Chunk.fromIterable(_))
}
