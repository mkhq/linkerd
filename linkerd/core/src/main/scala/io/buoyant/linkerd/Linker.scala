package io.buoyant.linkerd

import com.twitter.finagle.buoyant.DstBindingFactory
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.util.LoadService
import com.twitter.finagle.{Namer, Path, Stack}
import io.buoyant.linkerd.config.Parser

/**
 * Represents the total configuration of a Linkerd process.
 *
 * `params` are default router params defined in the top-level of a
 * linker configuration, and are used when reading [[Router Routers]].
 */
trait Linker {
  def routers: Seq[Router]
  def admin: Admin
}

object Linker {

  def load(config: String): Linker = {
    val protocols = LoadService[ProtocolInitializer]
    val namers = LoadService[NamerInitializer]
    val clientTls = LoadService[TlsClientInitializer]
    val mapper = Parser.objectMapper(config)
    for (p <- protocols) p.registerSubtypes(mapper)
    for (n <- namers) n.registerSubtypes(mapper)
    for (c <- clientTls) c.registerSubtypes(mapper)
    // TODO: Store the LinkerConfig so that it can be serialized out later
    mapper.readValue[LinkerConfig](config).mk
  }

  case class LinkerConfig(namerConfigs: Option[Seq[NamerConfig]], routerConfigs: Seq[RouterConfig], admin: Option[Admin]) {
    def mk: Linker = {
      val interpreter = nameInterpreter(namerConfigs.toSeq.flatten)
      val namersParam = Stack.Params.empty + DstBindingFactory.Namer(interpreter)
      val routers: Seq[Router] = routerConfigs.map(_.router(namersParam))
      // TODO: validate at least one router
      // TODO: validate no router names conflict
      // TODO: validate no server sockets conflict
      new Impl(routers, admin.getOrElse(Admin()))
    }
  }

  def nameInterpreter(namers: Seq[NamerConfig]): NameInterpreter =
    Interpreter(namers.map { cfg =>
      cfg.getPrefix -> cfg.newNamer()
    })

  /**
   * Private concrete implementation, to help protect compatibility if
   * the Linker api is extended.
   */
  private case class Impl(
    routers: Seq[Router],
    admin: Admin
  ) extends Linker
}
