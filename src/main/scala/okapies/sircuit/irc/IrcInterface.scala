package okapies.sircuit
package irc

import java.net.InetSocketAddress

import akka.actor._
import akka.io._
import akka.io.IO
import TcpPipelineHandler.{Init, WithinActorContext}
import akka.util.ByteString

object IrcInterfaceActor {

  def props() = Props(classOf[IrcInterfaceActor])

}

class IrcInterfaceActor extends Actor with ActorLogging {

  import Tcp._

  implicit def system = context.system

  private[this] val settings = Settings(system)

  private[this] val endpoint = new InetSocketAddress(settings.IrcHostname, settings.IrcPort)

  IO(Tcp) ! Tcp.Bind(self, endpoint)

  def receive: Receive = {
    case _: Bound => context.become(bound(sender))
  }

  def bound(listener: ActorRef): Receive = {
    case Connected(remote, _) =>
      log.info("Received connection from {}", remote)

      val connection = sender

      // Registers a handler to the connection
      val init = TcpPipelineHandler.withLogger(log,
        new IrcMessageStage >>
          new StringByteStringAdapter(settings.IrcCharset) >>
          new DelimiterFraming(
            maxSize = 512, delimiter = ByteString("\r\n"), includeDelimiter = false) >>
          new TcpReadWriteAdapter >>
          // new SslTlsSupport(sslEngine(remote, client = false)) >>
          new BackpressureBuffer(lowBytes = 50, highBytes = 500, maxBytes = 1000000))

      val addr = remote.getAddress.getHostAddress
      val port = remote.getPort
      val handler = context.actorOf(IrcHandler.props(init, connection, remote), s"${addr}_$port")
      val pipeline = context.actorOf(TcpPipelineHandler.props(init, connection, handler))

      connection ! Register(pipeline)
  }

}

object IrcHandler {

  def props(
      init: Init[WithinActorContext, IrcMessage, IrcMessage],
      connection: ActorRef,
      remote: InetSocketAddress) =
    Props(classOf[IrcHandler], init, connection, remote)

}

sealed trait ConnectionState
case object Registering extends ConnectionState
case object Registered extends ConnectionState

case class Client(password: Option[String],
                  nickname: Option[String],
                  username: Option[String])

class IrcHandler(
    init: Init[WithinActorContext, IrcMessage, IrcMessage],
    connection: ActorRef,
    remote: InetSocketAddress
  ) extends Actor with FSM[ConnectionState, Client] with ActorLogging {

  import Tcp._

  implicit def system = context.system

  private[this] val settings = Settings(system)

  val servername = settings.IrcHostname

  /* Watches for when the connection dies without sending a Tcp.ConnectionClosed */
  context watch connection

  /* Configures the state machine */

  startWith(Registering, Client(None, None, None))

  when(Registering) {
    case Event(init.Event(IrcMessage(_, "PASS", params)), prev) =>
      validate(params, min = 1) {
        Some(prev.copy(password = Option(params(0))))
      }.map(stay().using).getOrElse(stay())
    case Event(init.Event(IrcMessage(_, "NICK", params)), prev) =>
      validate(params, min = 1) {
        Some(prev.copy(nickname = Option(params(0))))
      }.map(stay().using).getOrElse(stay())
    case Event(init.Event(IrcMessage(_, "USER", params)), prev) =>
      validate(params, min = 1) {
        send("001", Seq(prev.nickname.getOrElse("*"), s"Welcome to $servername."))
        send("004", Seq(prev.nickname.getOrElse("*"), "localhost", "Sircuit"))
        /*
        sendMotd(
          nick = nick,
          start = "- Message of the Day -",
          motd = Seq("- Hello. Welcome to localhost."),
          end = "End of /MOTD command."
        )
        */
        Some(prev.copy(username = Option(params(0))))
      }.map(goto(Registered).using).getOrElse(stay())
  }

  when(Registered) {
    case Event(init.Event(IrcMessage(_, "JOIN", params)), client) =>
      stay()
  }

  whenUnhandled {
    case Event(init.Event(IrcMessage(_, "QUIT", params)), _) =>
      val quitMessage =
        if (params.length > 0) {
          params(0)
        } else {
          ""
        }
      send("ERROR", Seq(s"""Closing Link: ("$quitMessage")""")) // acknowledge QUIT command

      // sends Close event to keep the connection open to close it gracefully.
      closeGracefully()
      stay()
    case Event(init.Event(IrcMessage(_, command, _)), _) =>
      stateName match {
        case Registering =>
          send(servername, "451", Seq("*", "You have not registered")) // ERR_NOTREGISTERED
        case _ =>
          send(servername, "421", Seq(command, s"$command command is unknown by Sircuit"))
      }
      stay()
    case Event(_: ConnectionClosed, _) =>
      log.info("Connection closed")
      stop()
    case Event(Terminated(`connection`), _) =>
      log.info("Connection died")
      stop()
  }

  initialize()

  /* Utility methods */

  private[this] def send(command: String): Unit = send(IrcMessage(None, command, Nil))

  private[this] def send(command: String, params: Seq[String]): Unit =
    send(IrcMessage(None, command, params))

  private[this] def send(prefix: String, command: String, params: Seq[String]): Unit =
    send(IrcMessage(Option(prefix), command, params))

  private[this] def send(msg: IrcMessage): Unit = {
    log.debug("sending: " + msg.asProtocol)
    sender ! init.Command(msg)
  }

  private[this] def sendMotd(nick: String, start: String, motd: Seq[String], end: String) = {
    send("375", Seq(nick, start))
    motd foreach (line => send("372", Seq(nick, line)))
    send("376", Seq(nick, end))
  }

  private[this] def validate[A](params: Seq[String], min: Int)(f: => Option[A]) =
    if (params.length >= min) {
      f
    } else {
      send("461", "PASS", Seq("Not enough parameters")) // ERR_NEEDMOREPARAMS
      None
    }

  /**
   * You MUST handle `ConnectionClosed` event to stop this actor when use this method.
   */
  private[this] def closeGracefully() {
    sender ! TcpPipelineHandler.Management(Tcp.Close)
  }

}
