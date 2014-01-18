package okapies.sircuit
package irc

import java.net.InetSocketAddress

import akka.actor._
import akka.io._
import akka.io.IO
import TcpPipelineHandler.{Init, WithinActorContext}
import akka.util.ByteString

object IrcInterfaceActor {

  def props(gateway: ActorRef) = Props(classOf[IrcInterfaceActor], gateway)

}

class IrcInterfaceActor(gateway: ActorRef) extends Actor with ActorLogging {

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
      val handler = context.actorOf(
        IrcHandler.props(init, connection, remote, gateway), s"${addr}_$port")
      val pipeline = context.actorOf(TcpPipelineHandler.props(init, connection, handler))

      connection ! Register(pipeline)
  }

}

object IrcHandler {

  sealed trait State
  case object Registering extends State
  case object Registered extends State

  case class Client(password: Option[String],
                    nickname: String,
                    username: String,
                    pipeline: Option[ActorRef])

  def props(
      init: Init[WithinActorContext, IrcMessage, IrcMessage],
      connection: ActorRef,
      remote: InetSocketAddress,
      gateway: ActorRef) =
    Props(classOf[IrcHandler], init, connection, remote, gateway)

}

import IrcHandler._

class IrcHandler(
    init: Init[WithinActorContext, IrcMessage, IrcMessage],
    connection: ActorRef,
    remote: InetSocketAddress,
    gateway: ActorRef
  ) extends Actor with FSM[State, Client] with ActorLogging {

  import Tcp.{ConnectionClosed, Message => _}

  implicit def system = context.system

  private[this] val settings = Settings(system)

  val servername = settings.IrcHostname

  /* Watches for when the connection dies without sending a Tcp.ConnectionClosed */
  context watch connection

  /* Configures the state machine */

  startWith(Registering, Client(None, null, null, None))

  when(Registering) {
    case Event(init.Event(IrcMessage(_, "PASS", params)), client) =>
      validate("PASS", params, min = 1) {
        Some(client.copy(password = Option(params(0))))
      }.map(nextRegisteringState).getOrElse(stay())
    case Event(init.Event(IrcMessage(_, "NICK", params)), client) =>
      validate("NICK", params, min = 1) {
        Some(client.copy(nickname = params(0)))
      }.map(nextRegisteringState).getOrElse(stay())
    case Event(init.Event(IrcMessage(_, "USER", params)), client) =>
      validate("USER", params, min = 1) {
        // Sircuit doesn't use username, mode and realname.
        Some(client.copy(username = params(0)))
      }.map(nextRegisteringState).getOrElse(stay())
  }

  private[this] def nextRegisteringState(client: Client): State =
    if (client.nickname != null && client.username != null) {
      send("001", Seq(client.nickname, s"Welcome to the Sircuit"))
      /*
      sendMotd(
        nick = nick,
        start = "- Message of the Day -",
        motd = Seq("- Hello. Welcome to localhost."),
        end = "End of /MOTD command."
      )
      */
      goto(Registered) using client.copy(pipeline = Option(sender))
    } else {
      stay() using client
    }

  when(Registered) {
    handleIrcCommand orElse handleAdvertisement orElse handleErrorResponse
  }

  def handleIrcCommand: StateFunction = {
    case Event(init.Event(IrcMessage(_, "JOIN", params)), client) =>
      validate("JOIN", params, min = 1) {
        val channels = params(0).split(",")
        channels.foreach { channel =>
          validateChannelName(channel) { target =>
            gateway ! SubscribeRequest(self, RoomId(target), UserId(client.nickname))
          }
        }
        None
      }
      stay()
    case Event(res: SubscribeResponse, client) =>
      val nickname = client.nickname
      val channelName = res.room.name
      send(nickname, "JOIN", Seq(s"#$channelName"))
      res.topic match {
        case Some(topic) => // RPL_TOPIC
          send("332", Seq(nickname, s"#$channelName", topic))
        case None => // RPL_NOTOPIC
          send("331", Seq(nickname, s"#$channelName", "No topic is set"))
      }
      res.members.foreach { member =>
        send("353", Seq(nickname, "=", s"#$channelName", member.name)) // RPL_NAMREPLY
      }
      send("366", Seq(nickname, s"#$channelName", "End of NAMES list")) // RPL_ENDOFNAMES
      stay()
    case Event(init.Event(IrcMessage(_, "PART", params)), client) =>
      validate("PART", params, min = 1) {
        val channels = params(0).split(",")
        val message = params.applyOrElse(1, (_: Int) => "")
        channels.foreach { channel =>
          validateChannelName(channel) { target =>
            gateway ! UnsubscribeRequest(self, RoomId(target), UserId(client.nickname), message)
          }
        }
        None
      }
      stay()
    case Event(init.Event(IrcMessage(_, "MODE", params)), client) =>
      // NOTE: This command is currently not supported.
      val channel = params.headOption.getOrElse("*")
      // ERR_NOCHANMODES
      send("477", Seq(client.nickname, channel, "Sircuit doesn't support any channel modes"))
      stay()
    case Event(init.Event(IrcMessage(_, "PRIVMSG", params)), client) =>
      handleIrcMessageCommand("PRIVMSG", params, client, false)
    case Event(init.Event(IrcMessage(_, "NOTICE", params)), client) =>
      handleIrcMessageCommand("NOTICE", params, client, true)
    case Event(init.Event(IrcMessage(_, "TOPIC", params)), client) =>
      validate("TOPIC", params, min = 1) {
        val channel = params(0)
        validateChannelName(channel) { target =>
          val topic = Option(params.applyOrElse(1, (_: Int) => null))
          gateway ! UpdateTopicRequest(self, RoomId(target), UserId(client.nickname), topic)
        }
        None
      }
      stay()
  }

  private[this] def validateChannelName(channel: String)(f: String => Unit) =
    if (channel.startsWith("#")) {
      f(channel.tail)
    } else {
      // ERR_NOSUCHCHANNEL
      send("403", Seq(stateData.nickname, channel, "No such channel"))
    }

  private[this] def handleIrcMessageCommand(
      command: String, params: Seq[String], client: Client, isNotify: Boolean): State = {
    validate(command, params, min = 2) {
      val name = params(0)
      val message = params(1)
      val target = name.head match {
        case '#' => RoomId(name.tail)
        case _ => UserId(name)
      }
      if (!isNotify) {
        gateway ! MessageRequest(self, target, UserId(client.nickname), message)
      } else {
        gateway ! NotificationRequest(self, target, UserId(client.nickname), message)
      }
      None
    }
    stay()
  }

  private[this] def handleErrorResponse: StateFunction = {
    case Event(res: NoSuchRoomError, client) =>
      // ERR_NOSUCHCHANNEL
      send("403", Seq(client.nickname, res.room.name, "No such channel"))
      stay()
  }

  private[this] def handleAdvertisement: StateFunction = {
    case Event(ad: Message, client) =>
      val target = ad.target match {
        case UserId(name) => name
        case RoomId(name) => s"#$name"
      }
      send(ad.origin.name, "PRIVMSG", Seq(target, ad.message))
      stay()
    case Event(ad: Notification, client) =>
      val target = ad.target match {
        case UserId(name) => name
        case RoomId(name) => s"#$name"
      }
      send(ad.origin.name, "NOTICE", Seq(target, ad.message))
      stay()
    case Event(ad: ClientSubscribed, client) =>
      send(ad.user.name, "JOIN", Seq(s"#${ad.room.name}"))
      stay()
    case Event(ad: ClientUnsubscribed, client) =>
      send(ad.user.name, "PART", Seq(s"#${ad.room.name}", ad.message))
      stay()
    case Event(ad: TopicUpdated, client) =>
      ad.topic match {
        case Some(topic) =>
          send(ad.user.name, "TOPIC", Seq(s"#${ad.room.name}", topic))
        case None =>
          send(ad.user.name, "TOPIC", Seq(s"#${ad.room.name}"))
      }
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
          // ERR_NOTREGISTERED
          send(servername, "451", Seq("*", "You have not registered"))
        case _ =>
          if (command == "PASS" || command == "NICK" || command == "USER") {
            // ERR_ALREADYREGISTRED
            send(servername, "462",
              Seq(command, s"$command is unauthorized command (already registered)"))
          } else {
            // ERR_UNKNOWNCOMMAND
            send(servername, "421",
              Seq(command, s"$command is unknown command"))
          }
      }
      stay()
    case Event(evt: SircuitEvent, _) =>
      log.warning("Unknown event: {}", evt)
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

  private[this] def send(command: String, params: Seq[String]): Unit =
    send(IrcMessage(None, command, params))

  private[this] def send(prefix: String, command: String, params: Seq[String]): Unit =
    send(IrcMessage(Option(prefix), command, params))

  private[this] def send(msg: IrcMessage): Unit = {
    stateData.pipeline.getOrElse(sender) ! init.Command(msg)
  }

  private[this] def sendMotd(nick: String, start: String, motd: Seq[String], end: String) = {
    send("375", Seq(nick, start))
    motd foreach (line => send("372", Seq(nick, line)))
    send("376", Seq(nick, end))
  }

  private[this] def validate[A](command: String, params: Seq[String], min: Int)(f: => Option[A]) =
    if (params.length >= min) {
      f
    } else {
      val nickname = Option(stateData.nickname).getOrElse("*")
      send("461", Seq(nickname, command, "Not enough parameters")) // ERR_NEEDMOREPARAMS
      None
    }

  /**
   * You MUST handle `ConnectionClosed` event to stop this actor when use this method.
   */
  private[this] def closeGracefully() {
    stateData.pipeline.getOrElse(sender) ! TcpPipelineHandler.Management(Tcp.Close)
  }

}
