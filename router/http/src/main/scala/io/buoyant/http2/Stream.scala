package io.buoyant.router.http2

object Stream {

  sealed trait State
  object Idle extends State
  object Reserved extends State
  object Open extends State
  object HalfClosed extends State
  object Closed extends State
}
