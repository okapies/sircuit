package okapies.sircuit
package api.irc

import java.net.InetSocketAddress

import scala.concurrent.{Await, Future, promise}
import scala.concurrent.duration._

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

  private[this] val endpoint = settings.IrcBindAddress match {
    case Some(hostname) => new InetSocketAddress(hostname, settings.IrcBindPort)
    case None => new InetSocketAddress(settings.IrcBindPort)
  }

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
          // TODO: This is quick fix. Only support LF and CRLF.
          new RemoveCrStage >>
          new DelimiterFraming(
            maxSize = 512, delimiter = ByteString("\n"), includeDelimiter = false) >>
          new TcpReadWriteAdapter >>
          // new SslTlsSupport(sslEngine(remote, client = false)) >>
          new BackpressureBuffer(lowBytes = 100, highBytes = 1000, maxBytes = 1000000))

      val addr = remote.getAddress.getHostAddress
      val port = remote.getPort
      val pipelineP = promise[ActorRef]()
      val handler = context.actorOf(
        IrcHandler.props(init, connection, pipelineP.future, remote, gateway), s"${addr}_$port")
      val pipeline = context.actorOf(TcpPipelineHandler.props(init, connection, handler))
      pipelineP.success(pipeline)

      connection ! Register(pipeline)
  }

}

object IrcHandler {

  sealed trait State
  case object Registering extends State
  case object Registered extends State

  private[irc] case class Client(password: Option[String],
                                 nickname: String,
                                 isUserAccepted: Boolean,
                                 joinedChannels: Map[String, ActorRef],
                                 pingTimer: Cancellable)

  private[irc] case class PingTimer(active: Boolean)

  def props(
      init: Init[WithinActorContext, IrcMessage, IrcMessage],
      connection: ActorRef,
      pipelineFuture: Future[ActorRef],
      remote: InetSocketAddress,
      gateway: ActorRef) =
    Props(classOf[IrcHandler], init, connection, pipelineFuture, remote, gateway)

}

import IrcHandler._

class IrcHandler(
    init: Init[WithinActorContext, IrcMessage, IrcMessage],
    connection: ActorRef,
    pipelineFuture: Future[ActorRef],
    remote: InetSocketAddress,
    gateway: ActorRef
  ) extends Actor with FSM[State, Client] with ActorLogging {

  import Tcp.{ConnectionClosed, Message => _}
  import okapies.sircuit.{Event => SircuitEvent}

  private[this] val system = context.system

  import system.dispatcher // ExecutionContext used for actors

  private[this] var pipeline: ActorRef = _

  private[this] val settings = Settings(system)

  private[this] val servername = settings.IrcServername

  private[this] val pingFrequency = settings.IrcConnectPingFrequency

  /* Watches for when the connection dies without sending a Tcp.ConnectionClosed */
  context watch connection

  override def preStart(): Unit = {
    pipeline = Await.result(pipelineFuture, Long.MaxValue.nanoseconds)
  }

  /* Configures the state machine */

  startWith(
    Registering,
    Client(
      password = None,
      nickname = null,
      isUserAccepted = false,
      joinedChannels = Map.empty,
      pingTimer = null))

  when(Registering, settings.IrcConnectTimeout) {
    handleIrcRegistrationCommand   orElse
    handleNoIrcRegistrationCommand orElse
    handleRegistrationTimeout
  }

  when(Registered) {
    handleIrcCommandAndResetPingTimer orElse
    handleIrcCommandResponse          orElse
    handleAdvertisementEvent          orElse
    handlePingTimerEvent
  }

  whenUnhandled {
    handleConnectionClose orElse
    handleUnknownEvent    orElse
    handleBackpressureBufferEvent
  }

  /* Event handler implementations */

  private[this] def handleIrcRegistrationCommand: StateFunction = ({
    case Event(init.Event(IrcMessage(_, "PASS", params)), client) =>
      validate("PASS", params, min = 1) {
        Some(client.copy(password = Option(params(0))))
      }.map(nextRegisteringState).getOrElse(stay())
    case Event(init.Event(IrcMessage(_, "NICK", params)), client) =>
      validate("NICK", params, min = 1) {
        Some(client.copy(nickname = params(0)))
      }.map(nextRegisteringState).getOrElse(stay())
    case Event(init.Event(IrcMessage(_, "USER", params)), client) =>
      // Sircuit doesn't use username, mode and realname.
      nextRegisteringState(client.copy(isUserAccepted = true))
  }: StateFunction) orElse handleIrcQuitCommand

  private[this] def nextRegisteringState(client: Client): State =
    if (client.nickname != null && client.isUserAccepted) {
      gateway ! ClientOnline(self, UserId(client.nickname))

      // TODO: send full welcome messages
      val nickname = client.nickname
      send(servername, "001", Seq(nickname, "Welcome to the Sircuit Chat Server"))
      send(servername, "002", Seq(nickname,
        s"Your host is $servername, running version sircuit ?.?.?"))
      send(servername, "003", Seq(nickname,
        "This server was created ??? ??? ?? ???? at ??:??:?? ???"))
      // some clients require '004' to know completion of registration
      send(servername, "004", Seq(nickname, s"$servername ?.?.? i npst"))
      // RPL_ISUPPORT
      // see http://tools.ietf.org/html/draft-brocklesby-irc-isupport-03
      send(servername, "005", Seq(nickname,
        "PREFIX= CHANTYPES=# MODES=3 NICKLEN=16 TOPICLEN=255 "
          + "CHANMODES=,,,inpst CASEMAPPING=rfc1459", //
        ":are supported by this server"))
      // some clients require '251' following '005' to know completion of registration
      send(servername, "251",
        Seq(nickname, "There are ? users and ? invisible on ? servers"))
      /*
      sendMotd(
        nick = nick,
        start = "- Message of the Day -",
        motd = Seq("- Hello. Welcome to localhost."),
        end = "End of /MOTD command."
      )
      */

      // start PingTimer
      goto(Registered) using client.copy(pingTimer =
        system.scheduler.scheduleOnce(pingFrequency, self, PingTimer(active = true)))
    } else {
      stay() using client
    }

  private[this] def handleNoIrcRegistrationCommand: StateFunction = {
    case Event(init.Event(IrcMessage(_, command, _)), client) =>
      // ERR_NOTREGISTERED
      send(servername, "451", Seq("*", "You have not registered"))
      stay()
  }

  private[this] def handleRegistrationTimeout: StateFunction = {
    case Event(StateTimeout, client) =>
      send("ERROR", Seq(s"""Closing Link: (Ping timeout)"""))
      closeGracefully()
      stay()
  }

  private[this] def handleIrcCommandAndResetPingTimer: StateFunction =
    (
      handleIrcCommand     orElse
      handleIrcQuitCommand orElse
      handleUnknownIrcCommand
    ) andThen { state: State => // reset pingTimer when receive any IRC commands
      val client = state.stateData
      client.pingTimer.cancel()
      state using client.copy(pingTimer =
        system.scheduler.scheduleOnce(pingFrequency, self, PingTimer(active = true)))
    }

  private[this] def handleIrcCommand: StateFunction = {
    case Event(init.Event(IrcMessage(_, "JOIN", params)), client) =>
      validate("JOIN", params, min = 1) {
        val channels = params(0).split(",")
        val nickname = client.nickname
        channels.foreach { channel => channel.head match {
          case '#' =>
            val target = channel.tail
            gateway ! SubscribeRequest(self, ChannelId(target), UserId(nickname))
          case _ =>
            // ERR_NOSUCHCHANNEL
            send(servername, "403", Seq(nickname, channel, "No such channel"))
        }
        }
        None
      }
      stay()
    case Event(init.Event(IrcMessage(_, "PART", params)), client) =>
      validate("PART", params, min = 1) {
        val channels = params(0).split(",")
        val message = params.applyOrElse(1, (_: Int) => "")
        channels.foreach { channel =>
          val target = extractChannelName(channel)
          sendChannelRequest(client.joinedChannels, target) {
            _ ! UnsubscribeRequest(self, ChannelId(target), UserId(client.nickname), message)
          }
        }
        None
      }
      stay()
    case Event(init.Event(IrcMessage(_, "MODE", params)), client) =>
      validate("MODE", params, min = 1) {
        // ignores MODE command for both user and channel
        val target = params(0)
        target.head match {
          case '#' => send(clientPrefix, "MODE", params)
          case _ if target == client.nickname => send(client.nickname, "MODE", params)
          case _ =>
            // ERR_USERSDONTMATCH
            send(servername, "502", Seq(client.nickname, "Cannot change mode for other users"))
        }
        None
      }
      stay()
    case Event(init.Event(IrcMessage(_, "USERHOST", params)), client) =>
      validate("USERHOST", params, min = 1) {
        // RPL_USERHOST with invalid hostname
        params.foreach {
          case nickname if nickname == client.nickname =>
            val host = remote.getAddress.getHostAddress
            send(servername, "302", Seq(client.nickname, s"$nickname=+$nickname@$host"))
          case nickname =>
            send(servername, "302", Seq(client.nickname, s"$nickname=+$nickname@*"))
        }
        None
      }
      stay()
    case Event(init.Event(IrcMessage(_, "PING", params)), client) =>
      send(servername, "PONG", params)
      stay()
    case Event(init.Event(IrcMessage(_, "PONG", _)), client) =>
      stay()
    case Event(init.Event(IrcMessage(_, "PRIVMSG", params)), client) =>
      handleIrcMessageCommand("PRIVMSG", params, client, false)
    case Event(init.Event(IrcMessage(_, "NOTICE", params)), client) =>
      handleIrcMessageCommand("NOTICE", params, client, true)
    case Event(init.Event(IrcMessage(_, "TOPIC", params)), client) =>
      validate("TOPIC", params, min = 1) {
        val target = extractChannelName(params(0))
        val topic = Option(params.applyOrElse(1, (_: Int) => null))
        sendChannelRequest(client.joinedChannels, target) {
          _ ! UpdateTopicRequest(self, ChannelId(target), UserId(client.nickname), topic)
        }
        None
      }
      stay()
    case Event(init.Event(IrcMessage(_, "WHO", params)), client) =>
      validate("WHO", params, min = 1) {
        val mask = params(0)
        mask.head match {
          case '#' =>
            val target = extractChannelName(mask)
            sendChannelRequest(client.joinedChannels, target) {
              _ ! UserInfoRequest(self, ChannelId(target))
            }
          case _ =>
            val target = mask
            gateway ! UserInfoRequest(self, UserId(target))
        }
        None
      }
      stay()
  }

  private[this] def handleIrcMessageCommand(
      command: String, params: Seq[String], client: Client, isNotify: Boolean): State = {
    validate(command, params, min = 2) {
      val name = params(0)
      val message = params(1)
      val nickname = client.nickname
      name.head match {
        case '#' =>
          val target = extractChannelName(name)
          sendChannelRequest(client.joinedChannels, target) {
            _ ! (isNotify match {
              case false =>
                MessageRequest(self, ChannelId(target), UserId(nickname), message)
              case true =>
                NotificationRequest(self, ChannelId(target), UserId(nickname), message)
            })
          }
        case _ =>
          // TODO
          val target = name
          gateway ! (isNotify match {
            case false =>
              MessageRequest(self, UserId(target), UserId(nickname), message)
            case true =>
              NotificationRequest(self, UserId(target), UserId(nickname), message)
          })
      }
      None
    }
    stay()
  }

  private[this] def handleIrcQuitCommand: StateFunction = {
    case Event(init.Event(IrcMessage(_, "QUIT", params)), client) =>
      val quitMessage = if (params.length > 0) params(0) else ""

      stateName match {
        case Registering =>
          // do nothing because this client is not registered yet.
        case _ =>
          // broadcast unsubscribe message
          val nickname = client.nickname
          client.joinedChannels.foreach { case (name, chRef) =>
            chRef ! UnsubscribeRequest(self, ChannelId(name), UserId(nickname), quitMessage)
          }

          // notify service this client goes offline
          gateway ! ClientOffline(self, UserId(client.nickname))
      }

      // indicates QUIT command is acknowledged
      send("ERROR", Seq(s"""Closing Link: ("$quitMessage")"""))
      closeGracefully()
      stay()
  }

  private[this] def handleUnknownIrcCommand: StateFunction = {
    case Event(init.Event(IrcMessage(_, command, _)), client) =>
      if (command == "PASS" || command == "NICK" || command == "USER") {
        // ERR_ALREADYREGISTRED
        send(servername, "462", Seq(client.nickname,
          command, s"$command is unauthorized command (already registered)"))
      } else {
        // ERR_UNKNOWNCOMMAND
        send(servername, "421", Seq(client.nickname,
          command, s"$command is unknown command"))
      }
      stay()
  }

  private[this] def handleIrcCommandResponse: StateFunction = {
    case Event(res: SubscribeResponse, client) =>
      val nickname = client.nickname
      val channelName = res.channel.name
      val chRef = res.sender
      send(clientPrefix, "JOIN", Seq(s"#$channelName"))
      res.topic match {
        case Some(topic) => // RPL_TOPIC
          send(servername, "332", Seq(nickname, s"#$channelName", topic))
        case None => // RPL_NOTOPIC
          send(servername, "331", Seq(nickname, s"#$channelName", "No topic is set"))
      }
      res.members.foreach { member =>
        // RPL_NAMREPLY
        send(servername, "353", Seq(nickname, "@", s"#$channelName", member.name))
      }
      // RPL_ENDOFNAMES
      send(servername, "366", Seq(nickname, s"#$channelName", "End of NAMES list"))

      stay using client.copy(joinedChannels = client.joinedChannels + (channelName -> chRef))
    case Event(res: UnsubscribeResponse, client) =>
      val channelName = res.channel.name
      send(clientPrefix, "PART", Seq(s"#$channelName", res.message))
      stay using client.copy(joinedChannels = client.joinedChannels - channelName)
    case Event(res: NoSuchChannelError, client) =>
      // ERR_NOSUCHCHANNEL
      send(servername, "403", Seq(client.nickname, res.channel.name, "No such channel"))
      stay()
  }

  private[this] def handleAdvertisementEvent: StateFunction = {
    case Event(ad: Message, client) =>
      val target = ad.target match {
        case UserId(name) => name
        case ChannelId(name) => s"#$name"
      }
      send(userPrefix(ad.origin.name), "PRIVMSG", Seq(target, ad.message))
      stay()
    case Event(ad: Notification, client) =>
      val target = ad.target match {
        case UserId(name) => name
        case ChannelId(name) => s"#$name"
      }
      send(userPrefix(ad.origin.name), "NOTICE", Seq(target, ad.message))
      stay()
    case Event(ad: ClientSubscribed, client) =>
      send(userPrefix(ad.user.name), "JOIN", Seq(s"#${ad.channel.name}"))
      stay()
    case Event(ad: ClientUnsubscribed, client) =>
      send(userPrefix(ad.user.name), "PART", Seq(s"#${ad.channel.name}", ad.message))
      stay()
    case Event(ad: TopicStatus, client) =>
      ad.topic match {
        case Some(topic) =>
          send(userPrefix(ad.user.name), "TOPIC", Seq(s"#${ad.channel.name}", topic))
        case None =>
          send(userPrefix(ad.user.name), "TOPIC", Seq(s"#${ad.channel.name}"))
      }
      stay()
    case Event(ad: ChannelMembers, client) =>
      val channelName = s"#${ad.channel.name}"
      ad.members foreach { member =>
        val nickname = member.id.name
        // RPL_WHOREPLY
        send(servername, "352", Seq(client.nickname,
          channelName, nickname, "*", servername, nickname, "H", s"0 $nickname"))
      }
      send(servername, "315", Seq(client.nickname, channelName, "End of WHO list."))
      stay()
  }

  private[this] def handlePingTimerEvent: StateFunction = {
    case Event(PingTimer(active), client) =>
      if (active) {
        send("PING", Seq(servername))
        // reset pingTimer to wait PONG from the client
        stay using client.copy(pingTimer =
          system.scheduler.scheduleOnce(pingFrequency, self, PingTimer(active = false)))
      } else {
        log.info("Ping timeout")
        gateway ! ClientOffline(self, UserId(client.nickname))

        send("ERROR", Seq(s"""Closing Link: (Ping timeout)"""))
        closeGracefully()
        stay()
      }
  }

  private[this] def handleConnectionClose: StateFunction = {
    case Event(_: ConnectionClosed, client) =>
      log.info("Connection closed")
      gateway ! ClientOffline(self, UserId(client.nickname))
      stop()
    case Event(Terminated(`connection`), client) =>
      log.info("Connection died")
      gateway ! ClientOffline(self, UserId(client.nickname))
      stop()
  }

  private[this] def handleBackpressureBufferEvent: StateFunction = {
    // TODO: do nothing at this time
    case _: BackpressureBuffer.HighWatermarkReached =>
      log.debug("the buffer's high watermark has been reached.")
      stay()
    case _: BackpressureBuffer.LowWatermarkReached =>
      log.debug("the buffer’s fill level falls below the low watermark.")
      stay()
  }

  private[this] def handleUnknownEvent: StateFunction = {
    case Event(evt: SircuitEvent, _) =>
      log.warning("Unknown event: {}", evt)
      stay()
  }

  initialize()

  /* Utility methods */

  private[this] def send(command: String, params: Seq[String]): Unit =
    send(IrcMessage(None, command, params))

  private[this] def send(prefix: String, command: String, params: Seq[String]): Unit =
    send(IrcMessage(Option(prefix), command, params))

  private[this] def send(msg: IrcMessage): Unit = {
    pipeline ! init.Command(msg)
  }

  private[this] def sendMotd(nick: String, start: String, motd: Seq[String], end: String) = {
    send(servername, "375", Seq(nick, start))
    motd foreach (line => send("372", Seq(nick, line)))
    send(servername, "376", Seq(nick, end))
  }

  private[this] def validate[A](command: String, params: Seq[String], min: Int)(f: => Option[A]) =
    if (params.length >= min) {
      f
    } else {
      val nickname = Option(stateData.nickname).getOrElse("*")
      send(servername, "461", Seq(nickname, command, "Not enough parameters")) // ERR_NEEDMOREPARAMS
      None
    }

  private[this] def extractChannelName(name: String) = name.head match {
    case '#' => name.tail
    case _ => name
  }

  private[this] def sendChannelRequest(
      channels: Map[String, ActorRef], channel: String)(f: ActorRef => Unit) = {
    channels.get(channel) match {
      case Some(chRef) => f(chRef)
      case _ =>
        // ERR_NOSUCHCHANNEL
        send(servername, "403", Seq(stateData.nickname, channel, "No such channel"))
    }
  }

  private[this] def userPrefix(nickname: String, host: String = "*") = s"$nickname!$nickname@$host"

  private[this] def clientPrefix = userPrefix(stateData.nickname, remote.getAddress.getHostAddress)

  /**
   * You MUST handle `ConnectionClosed` event to stop this actor when use this method.
   */
  private[this] def closeGracefully() {
    // sends Close event to keep the connection open to close it gracefully.
    pipeline ! TcpPipelineHandler.Management(Tcp.Close)
  }

}
