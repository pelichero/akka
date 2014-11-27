/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import java.io.Closeable
import java.net.InetSocketAddress
import com.typesafe.config.Config
import org.reactivestreams.{ Publisher, Subscriber }
import scala.collection.immutable
import akka.io.Inet
import akka.stream.MaterializerSettings
import akka.http.engine.client.ClientConnectionSettings
import akka.http.engine.server.ServerSettings
import akka.http.model.{ ErrorInfo, HttpResponse, HttpRequest, japi }
import akka.http.util._
import akka.actor._

object Http extends ExtensionKey[HttpExt] {

  /**
   * Command that can be sent to `IO(Http)` to trigger the setup of an HTTP client facility at
   * a certain API level (connection, host or request).
   * The HTTP layer will respond with an `Http.OutgoingChannel` reply (or `Status.Failure`).
   * The sender `ActorRef`of this response can then be sent `HttpRequest` instances to which
   * it will respond with `HttpResponse` instances (or `Status.Failure`).
   */
  sealed trait SetupOutgoingChannel

  final case class Connect(remoteAddress: InetSocketAddress,
                           localAddress: Option[InetSocketAddress],
                           options: immutable.Traversable[Inet.SocketOption],
                           settings: Option[ClientConnectionSettings],
                           materializerSettings: Option[MaterializerSettings]) extends SetupOutgoingChannel
  object Connect {
    def apply(host: String, port: Int = 80,
              localAddress: Option[InetSocketAddress] = None,
              options: immutable.Traversable[Inet.SocketOption] = Nil,
              settings: Option[ClientConnectionSettings] = None,
              materializerSettings: Option[MaterializerSettings] = None): Connect =
      apply(new InetSocketAddress(host, port), localAddress, options, settings, materializerSettings)
  }

  // PREVIEW OF COMING API HERE:
  //
  //  case class SetupHostConnector(host: String, port: Int = 80,
  //                                options: immutable.Traversable[Inet.SocketOption] = Nil,
  //                                settings: Option[HostConnectorSettings] = None,
  //                                connectionType: ClientConnectionType = ClientConnectionType.AutoProxied,
  //                                defaultHeaders: immutable.Seq[HttpHeader] = Nil) extends SetupOutgoingChannel {
  //    private[http] def normalized(implicit refFactory: ActorRefFactory) =
  //      if (settings.isDefined) this
  //      else copy(settings = Some(HostConnectorSettings(actorSystem)))
  //  }
  //  object SetupHostConnector {
  //    def apply(host: String, port: Int, sslEncryption: Boolean)(implicit refFactory: ActorRefFactory): SetupHostConnector =
  //      apply(host, port, sslEncryption).normalized
  //  }
  //  sealed trait ClientConnectionType
  //  object ClientConnectionType {
  //    object Direct extends ClientConnectionType
  //    object AutoProxied extends ClientConnectionType
  //    final case class Proxied(proxyHost: String, proxyPort: Int) extends ClientConnectionType
  //  }
  //
  //  case object SetupRequestChannel extends SetupOutgoingChannel

  /**
   * An `OutgoingHttpChannel` with a single outgoing HTTP connection as the underlying transport.
   */
  // FIXME: hook up with new style IO
  final case class OutgoingConnection(remoteAddress: InetSocketAddress,
                                      localAddress: InetSocketAddress,
                                      responsePublisher: Publisher[(HttpResponse, Any)],
                                      requestSubscriber: Subscriber[(HttpRequest, Any)]) {
  }

  // PREVIEW OF COMING API HERE:
  //
  //  /**
  //   * An `OutgoingHttpChannel` with a connection pool to a specific host/port as the underlying transport.
  //   */
  //  final case class HostChannel(host: String, port: Int,
  //                               untypedProcessor: HttpClientProcessor[Any]) extends OutgoingChannel {
  //    def processor[T] = untypedProcessor.asInstanceOf[HttpClientProcessor[T]]
  //  }
  //
  //  /**
  //   * A general `OutgoingHttpChannel` with connection pools to all possible host/port combinations
  //   * as the underlying transport.
  //   */
  //  final case class RequestChannel(untypedProcessor: HttpClientProcessor[Any]) extends OutgoingChannel {
  //    def processor[T] = untypedProcessor.asInstanceOf[HttpClientProcessor[T]]
  //  }

  final case class Bind(endpoint: InetSocketAddress,
                        backlog: Int,
                        options: immutable.Traversable[Inet.SocketOption],
                        serverSettings: Option[ServerSettings],
                        materializerSettings: Option[MaterializerSettings])
  object Bind {
    def apply(interface: String, port: Int = 80, backlog: Int = 100,
              options: immutable.Traversable[Inet.SocketOption] = Nil,
              serverSettings: Option[ServerSettings] = None,
              materializerSettings: Option[MaterializerSettings] = None): Bind =
      apply(new InetSocketAddress(interface, port), backlog, options, serverSettings, materializerSettings)
  }

  sealed abstract case class ServerBinding(localAddress: InetSocketAddress,
                                           connectionStream: Publisher[IncomingConnection]) extends model.japi.ServerBinding with Closeable {
    /** Java API */
    def getConnectionStream: Publisher[japi.IncomingConnection] = connectionStream.asInstanceOf[Publisher[japi.IncomingConnection]]
  }

  object ServerBinding {
    def apply(localAddress: InetSocketAddress, connectionStream: Publisher[IncomingConnection]): ServerBinding =
      new ServerBinding(localAddress, connectionStream) {
        override def close() = ()
      }

    def apply(localAddress: InetSocketAddress, connectionStream: Publisher[IncomingConnection], closeable: Closeable): ServerBinding =
      new ServerBinding(localAddress, connectionStream) {
        override def close() = closeable.close()
      }
  }

  final case class IncomingConnection(remoteAddress: InetSocketAddress,
                                      requestPublisher: Publisher[HttpRequest],
                                      responseSubscriber: Subscriber[HttpResponse]) extends model.japi.IncomingConnection {
    /** Java API */
    def getRequestPublisher: Publisher[japi.HttpRequest] = requestPublisher.asInstanceOf[Publisher[japi.HttpRequest]]
    /** Java API */
    def getResponseSubscriber: Subscriber[japi.HttpResponse] = responseSubscriber.asInstanceOf[Subscriber[japi.HttpResponse]]
  }

  case object BindFailedException extends SingletonException

  class ConnectionException(message: String) extends RuntimeException(message)

  class ConnectionAttemptFailedException(val endpoint: InetSocketAddress) extends ConnectionException(s"Connection attempt to $endpoint failed")

  class RequestTimeoutException(val request: HttpRequest, message: String) extends ConnectionException(message)

  class StreamException(val info: ErrorInfo) extends RuntimeException(info.summary)
}

class HttpExt(system: ExtendedActorSystem) extends akka.io.IO.Extension {
  val Settings = new Settings(system.settings.config getConfig "akka.http")
  class Settings private[HttpExt] (config: Config) {
    val ManagerDispatcher = config getString "manager-dispatcher"
  }

  val manager = system.actorOf(props = HttpManager.props(Settings), name = "IO-HTTP")
}