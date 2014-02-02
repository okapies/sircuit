package okapies.sircuit.api.http

import akka.actor.{Actor, ActorRef, Props}
import spray.routing._
import spray.http._
import MediaTypes._

import okapies.sircuit._

object RestInterfaceActor {

  def props(gateway: ActorRef) = Props(classOf[RestInterfaceActor], gateway)

}

// we don't implement our route structure directly in the service actor because
// we want to be able to test it independently, without having to spin up an actor
class RestInterfaceActor(gateway: ActorRef) extends Actor with RestInterface {

  // the HttpService trait defines only one abstract member, which
  // connects the services environment to the enclosing actor or test
  def actorRefFactory = context

  // this actor only runs our route, but you could add
  // other things here, like request stream processing
  // or timeout handling
  def receive = runRoute(route)

  def sendMessage(target: Identifier, origin: UserId, message: String) =
    gateway ! MessageRequest(self, target, origin, message)

  def sendNotification(target: Identifier, origin: UserId, message: String) =
    gateway ! NotificationRequest(self, target, origin, message)

}

// this trait defines our service behavior independently from the service actor
trait RestInterface extends HttpService {

  val route = roomRoute

  def roomRoute: Route =
    path("room" / Segment / "message") { roomId =>
      ((get | put) & parameters('auth_token, 'message)) { (auth_token, message) =>
        respondWithMediaType(`application/json`) {
          complete {
            sendMessage(RoomId(roomId), UserId(auth_token), message)
            "{result: \"ok\"}"
          }
        }
      }
    } ~
    path("room" / Segment / "notification") { roomId =>
      ((get | put) & parameters('auth_token, 'message)) { (auth_token, message) =>
        respondWithMediaType(`application/json`) {
          complete {
            sendMessage(RoomId(roomId), UserId(auth_token), message)
            "{result: \"ok\"}"
          }
        }
      }
    }

  def sendMessage(target: Identifier, origin: UserId, message: String): Unit

  def sendNotification(target: Identifier, origin: UserId, message: String): Unit

}
