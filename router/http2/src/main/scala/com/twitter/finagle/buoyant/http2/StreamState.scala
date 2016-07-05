package com.twitter.finagle.buoyant.http2

import com.twitter.io.Writer
import com.twitter.util.{Closable, Promise}
import io.netty.handler.codec.http2.Http2Headers

private[http2] sealed trait StreamState
private[http2] object StreamState {

  object Idle extends StreamState

  case class Open(
    writer: Writer with Closable,
    trailers: Promise[Option[Http2Headers]]
  ) extends StreamState

  case class HalfClosedLocal(trailers: Promise[Option[Http2Headers]]) extends StreamState

  object HalfClosedRemote extends StreamState

  object Closed extends StreamState
}
