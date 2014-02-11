package okapies.sircuit.api.http

import java.nio.ByteOrder.BIG_ENDIAN

import akka.actor._
import akka.actor.SupervisorStrategy.Stop
import akka.io.Tcp
import akka.util.ByteString
import spray.http._
import MediaTypes._
import spray.routing._
import spray.util.LoggingContext

import spray.can.server.websockets.Sockets
import spray.can.server.websockets.model.{Frame, OpCode}
import okapies.sircuit._

object RestInterfaceActor {

  def props(gateway: ActorRef) = Props(classOf[RestInterfaceActor], gateway)

}

class RestInterfaceActor(gateway: ActorRef) extends Actor with ActorLogging {

  def receive = {
    case msg: Tcp.Connected =>
      // create per connection handler
      val handler = context.actorOf(RestHandlerActor.props(sender, gateway))
      handler forward msg // to register handler in runRoute()
  }

}

object RestHandlerActor {

  def props(connection: ActorRef, gateway: ActorRef) =
    Props(classOf[RestHandlerActor], connection, gateway)

}

// per connection handler
class RestHandlerActor(connection: ActorRef, gateway: ActorRef)
  extends Actor with ActorLogging with RestHandler {

  private[this] var wsConnection: ActorRef = _

  private[this] var isHttp = true

  // the HttpService trait defines only one abstract member, which
  // connects the services environment to the enclosing actor or test
  def actorRefFactory = context

  // watches for when the connection dies without sending ConnectionClose message
  context watch connection

  def receive = runRoute(route)

  override def onConnectionClosed(ev: Tcp.ConnectionClosed) = context stop self

  def upgradeToWebSocket(reqCtx: RequestContext) = {
    context become receiveWebSocket
    connection ! Sockets.UpgradeServer(Sockets.acceptAllFunction(reqCtx.request), self)
  }

  private[this] def receiveWebSocket: Receive = {
    case Sockets.Upgraded =>
      wsConnection = sender
      context watch wsConnection
      isHttp = false
    case f @ Frame(fin, rsv, OpCode.Text, maskingKey, data) =>
      wsConnection ! Frame(
        opcode = OpCode.Text,
        data = ByteString(f.stringData.toUpperCase)
      )
    case _: Tcp.ConnectionClosed =>
      log.info("WebSocket connection closed")
      context stop self
    case Terminated(t) if t.path == connection.path || t.path == wsConnection.path =>
      log.info("WebSocket connection died")
      context stop self
  }

  def closeGracefully() =
    if (isHttp) {
      connection ! Tcp.Close
    } else {
      closeWebSocketConnection(1000)
    }

  private[this] def closeWebSocketConnection(statusCode: Short) =
    wsConnection ! Frame(
      opcode = OpCode.ConnectionClose,
      data = ByteString.newBuilder.putShort(statusCode)(BIG_ENDIAN).result()
    )

  override val supervisorStrategy =
    OneForOneStrategy() {
      case e =>
        complete(StatusCodes.InternalServerError, e.getMessage)
        closeGracefully()
        Stop
    }

  def sendMessageRequest(target: Identifier, origin: UserId, message: String) =
    gateway ! MessageRequest(self, target, origin, message)

  def sendNotificationRequest(target: Identifier, origin: UserId, message: String) =
    gateway ! NotificationRequest(self, target, origin, message)

}

// this trait defines our service behavior independently from the service actor
trait RestHandler extends HttpService {

  val route = roomRoute

  def roomRoute(implicit log: LoggingContext): Route =
    path("room" / Segment / "message") { roomId =>
      ((get | put) & parameters('auth_token, 'message)) { (auth_token, message) =>
        respondWithMediaType(`application/json`) {
          complete {
            sendMessageRequest(RoomId(roomId), UserId(auth_token), message)
            "{result: \"ok\"}"
          }
        }
      }
    } ~
    path("room" / Segment / "notification") { roomId =>
      ((get | put) & parameters('auth_token, 'message)) { (auth_token, message) =>
        respondWithMediaType(`application/json`) {
          complete {
            sendMessageRequest(RoomId(roomId), UserId(auth_token), message)
            "{result: \"ok\"}"
          }
        }
      }
    } ~
    (path("stream") & get) { ctx =>
      upgradeToWebSocket(ctx)
    }

  override def complete = marshallable => new StandardRoute {
    override def apply(ctx: RequestContext) = {
      ctx.complete(marshallable)
      closeGracefully()
    }
  }

  def upgradeToWebSocket(ctx: RequestContext): Unit

  def closeGracefully(): Unit

  def sendMessageRequest(target: Identifier, origin: UserId, message: String): Unit

  def sendNotificationRequest(target: Identifier, origin: UserId, message: String): Unit

}
