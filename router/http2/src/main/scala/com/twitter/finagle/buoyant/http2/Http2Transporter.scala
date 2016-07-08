package com.twitter.finagle.buoyant.http2

import com.twitter.finagle.Stack
import com.twitter.finagle.client.Transporter
import com.twitter.finagle.netty4.Netty4Transporter
import com.twitter.finagle.netty4.channel.BufferingChannelOutboundHandler
import com.twitter.finagle.transport.TransportProxy
import com.twitter.io.Charsets
import io.netty.channel._
import io.netty.handler.codec.http2._
import io.netty.handler.logging.{LogLevel, LoggingHandler}
import java.net.SocketAddress
import java.util.concurrent.atomic.AtomicBoolean

object Http2Transporter {

  private[this] val log = com.twitter.logging.Logger.get(getClass.getName)

  // XXX TODO this will need some more rigorous buffering...
  class Http2GreetingHandler extends ChannelDuplexHandler { handler =>
    override def channelRegistered(ctx: ChannelHandlerContext): Unit = {
      log.info(s"GREETER.channelRegistered: $ctx")
      val buf = Http2CodecUtil.connectionPrefaceBuf
      val _ = ctx.write(buf).addListener(new ChannelFutureListener {
        def operationComplete(greetF: ChannelFuture): Unit = {
          buf.release()
          log.info(s"GREETER.connect: greeted: $greetF")
          ctx.pipeline.remove(handler)

          if (greetF.isSuccess) {
            val _ = ctx.fireChannelRegistered()
          } else {
            val _ = ctx.fireExceptionCaught(greetF.cause)
          }
        }
      })
    }
  }

  def mk(params0: Stack.Params): Transporter[Http2StreamFrame, Http2StreamFrame] = {
    val initializer = { pipeline: ChannelPipeline =>
      // XXX this compile setting is sort of in the way, isn't it...
      val _wireDebug = pipeline.addLast("wire debug", new LoggingHandler(LogLevel.INFO))
      val _h2Greet = pipeline.addLast("h2 greeter", new Http2GreetingHandler)
      val _rawDebug = pipeline.addLast("raw debug", new DebugHandler("client[raw]"))
      val _h2 = pipeline.addLast("h2", new Http2FrameCodec(false /*server*/ ))
      val _h2Debug = pipeline.addLast("h2 debug", new DebugHandler("client[h2]"))
    }

    // Netty4's Http2 Codec doesn't support backpressure yet.
    // See https://github.com/netty/netty/issues/3667#issue-69640214
    val params = params0 + Netty4Transporter.Backpressure(false)

    Netty4Transporter(initializer, params)
  }

}
