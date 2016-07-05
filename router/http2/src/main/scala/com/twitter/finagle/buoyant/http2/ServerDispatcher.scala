package com.twitter.finagle.buoyant.http2

import com.twitter.finagle.{CancelledRequestException, Failure, Service}
import com.twitter.finagle.netty4.{BufAsByteBuf, ByteBufAsBuf}
import com.twitter.finagle.transport.Transport
import com.twitter.io.Reader
import com.twitter.logging.Logger
import com.twitter.util.{Closable, Future, Promise, Return, Throw, Time}
import io.netty.handler.codec.http2._
import scala.collection.JavaConverters._

object ServerDispatcher {
  private[this] val log = Logger.get(getClass.getName)

  def dispatch(
    transport: Transport[Http2StreamFrame, Http2StreamFrame],
    service: Service[Request, Response]
  ): Closable = {
    val streamStateMu = new {}
    var streamState: StreamState = StreamState.Idle

    def fail(e: Throwable, err: Http2Error = Http2Error.STREAM_CLOSED): Future[Unit] =
      streamStateMu.synchronized {
        streamState match {
          case StreamState.Closed =>
            Future.exception(e)

          case StreamState.HalfClosedLocal(trailers) =>
            streamState = StreamState.Closed
            transport.write(new DefaultHttp2ResetFrame(err))
              .transform { _ => transport.close() }
              .ensure { val _ = trailers.updateIfEmpty(Throw(e)) }
              .before(Future.exception(e))

          case _ =>
            streamState = StreamState.Closed
            transport.write(new DefaultHttp2ResetFrame(err))
              .transform { _ => transport.close() }
              .before(Future.exception(e))
        }
      }

    def writeMessage(rsp: Message): Future[Unit] =
      rsp.data match {
        case None =>
          writeHeaders(rsp.headers, true)

        case Some(data) =>
          writeHeaders(rsp.headers, false).flatMap { _ =>
            writeData(data.reader).flatMap { _ =>
              data.trailers.flatMap(writeTrailersOrEOS(_))
            }
          }
      }

    def writeData(reader: Reader): Future[Unit] = {
      // XXX read sizes should be tuned according to seconds
      def readWriteLoop(): Future[Unit] =
        reader.read(Int.MaxValue).flatMap {
          case None => Future.Unit
          case Some(buf) =>
            val frame = new DefaultHttp2DataFrame(BufAsByteBuf.Owned(buf), false)
            transport.write(frame).flatMap { _ => readWriteLoop() }
        }
      readWriteLoop()
    }

    def writeTrailersOrEOS(t: Option[Headers]): Future[Unit] = t match {
      case None =>
        transport.write(new DefaultHttp2DataFrame(true /* endStream */ ))
      case Some(trailers) =>
        writeHeaders(trailers, true)
    }

    def writeHeaders(orig: Headers, endsStream: Boolean): Future[Unit] = {
      val headers = orig match {
        case netty4: Netty4Headers => netty4.underlying
        case _ =>
          val headers = new DefaultHttp2Headers
          for ((k, v) <- orig.toSeq) headers.add(k, v)
          headers
      }
      transport.write(new DefaultHttp2HeadersFrame(headers, endsStream))
    }

    def readLoop(): Future[Unit] = {
      log.info(s"srv.dispatch: ${transport.remoteAddress} reading")
      transport.read().transform {
        case Throw(e) => fail(e)

        case Return(frame) =>
          log.info(s"srv.dispatch: ${transport.remoteAddress} read ${frame} @ ${streamState}")
          streamStateMu.synchronized {
            frame match {
              case _: Http2ResetFrame =>
                streamState = StreamState.Closed
                transport.close()

              case frame: Http2WindowUpdateFrame =>
                log.info(s"srv.dispatch: window update: $frame")
                readLoop()

              case frame: Http2HeadersFrame =>
                (streamState, frame.isEndStream) match {
                  case (StreamState.Idle, eos) =>
                    val req =
                      if (eos) {
                        streamState = StreamState.HalfClosedRemote
                        Request(RequestHeaders(frame.headers))
                      } else {
                        val rw = Reader.writable()
                        val trailersP = Promise[Option[Http2Headers]]
                        streamState = StreamState.Open(rw, trailersP)
                        val data = DataStream(rw, trailersP.map(_.map(Headers(_))))
                        Request(RequestHeaders(frame.headers), Some(data))
                      }

                    val _ = service(req).transform {
                      case Throw(e) => fail(e)
                      case Return(rsp) => writeMessage(rsp)
                    }

                    readLoop()

                  case (StreamState.Open(writer, promise), true) =>
                    streamState = StreamState.HalfClosedRemote
                    writer.close().transform { _ =>
                      promise.setValue(Some(frame.headers))
                      readLoop()
                    }

                  case (state, _) =>
                    log.error(s"srv.dispatch: unexpected state: $state")
                    fail(new IllegalStateException(s"unexpected state: $state"))
                }

              case frame: Http2DataFrame =>
                (streamState, frame.isEndStream) match {
                  case (StreamState.Open(data, _), false) =>
                    data.write(ByteBufAsBuf.Owned(frame.content)).transform { _ =>
                      readLoop()
                    }

                  case (StreamState.Open(data, _), true) =>
                    streamState = StreamState.HalfClosedRemote
                    data.write(ByteBufAsBuf.Owned(frame.content)).transform { _ =>
                      data.close().transform { _ =>
                        readLoop()
                      }
                    }

                  case (state, _) =>
                    log.error(s"srv.dispatch: unexpected state: $state")
                    fail(new IllegalStateException(s"unexpected state: $state"))
                }

              case obj =>
                log.error(s"srv.dispatch: unexpected frame type: $obj")
                fail(new IllegalStateException(s"unexpected frame type: $obj"))
            }
          }
      }
    }

    val readF = readLoop()
    Closable.make { deadline =>
      streamState = StreamState.Closed
      readF.raise(new CancelledRequestException)
      transport.close(deadline)
    }
  }
}
