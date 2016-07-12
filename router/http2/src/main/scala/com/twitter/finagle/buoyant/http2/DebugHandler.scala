package com.twitter.finagle.buoyant.http2

import com.twitter.logging.Logger
import io.netty.channel._
import io.netty.handler.codec.http2._

private[http2] class DebugHandler(prefix: String)
  extends ChannelDuplexHandler {

  private[this] val log = Logger.get(getClass.getName)

  override def handlerAdded(ctx: ChannelHandlerContext): Unit = {
    log.info(s"$prefix.handlerAdded ${ctx.channel}")
    super.handlerAdded(ctx)
  }

  override def handlerRemoved(ctx: ChannelHandlerContext): Unit = {
    log.info(s"$prefix.handlerRemoved ${ctx.channel}")
    super.handlerRemoved(ctx)
  }

  override def channelActive(ctx: ChannelHandlerContext): Unit = {
    log.info(s"$prefix.channelActive ${ctx.channel}")
    super.channelActive(ctx)
  }

  override def channelInactive(ctx: ChannelHandlerContext): Unit = {
    log.info(s"$prefix.channelInactive ${ctx.channel}")
    super.channelInactive(ctx)
  }

  override def channelRead(ctx: ChannelHandlerContext, obj: Any): Unit = {
    log.info(s"$prefix.channelRead ${ctx.channel} $obj")
    super.channelRead(ctx, obj)
  }

  override def channelReadComplete(ctx: ChannelHandlerContext): Unit = {
    log.info(s"$prefix.channelReadComplete ${ctx.channel}")
    super.channelReadComplete(ctx)
  }

  override def userEventTriggered(ctx: ChannelHandlerContext, ev: Any): Unit = {
    log.info(s"$prefix.userEventTriggered ${ctx.channel} $ev")
    val _ = ctx.fireUserEventTriggered(ev)
  }

  private[this] val counter = new java.util.concurrent.atomic.AtomicLong
  override def write(ctx: ChannelHandlerContext, obj: Any, p: ChannelPromise): Unit = {
    val id = counter.incrementAndGet()
    val objstr = obj match {
      case f: Http2HeadersFrame =>
        val eos = if (f.isEndStream) "eos" else "---"
        s"${f.streamId} $eos HEADERS"

      case f: Http2DataFrame =>
        val eos = if (f.isEndStream) "eos" else "---"
        s"${f.streamId} $eos DATA ${f.content.capacity}B"

      case f: Http2ResetFrame =>
        s"${f.streamId} eos ${f.name}"

      case f: Http2StreamFrame =>
        s"${f.streamId} --- ${f.name}"

      case obj => obj.toString
    }

    log.info(s"$prefix.write $id ${ctx.channel} [$objstr]")
    p.addListener(new ChannelFutureListener {
      override def operationComplete(cf: ChannelFuture): Unit = cf.cause match {
        case null => log.info(s"$prefix.write.complete $id ${ctx.channel} [$objstr] $cf")
        case e => log.info(e, s"$prefix.write.complete $id ${ctx.channel} [$objstr] $cf")
      }
    })
    super.write(ctx, obj, p)
  }

  override def close(ctx: ChannelHandlerContext, p: ChannelPromise): Unit = {
    log.info(s"$prefix.close ${ctx.channel}")
    p.addListener(new ChannelFutureListener {
      override def operationComplete(cf: ChannelFuture): Unit =
        log.info(s"$prefix.close.complete ${ctx.channel} $cf")
    })
    super.close(ctx, p)
  }
}
