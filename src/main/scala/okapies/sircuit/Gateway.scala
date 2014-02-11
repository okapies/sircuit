package okapies.sircuit

import scala.collection.mutable

import akka.actor._

object GatewayActor {

  def props() = Props(classOf[GatewayActor])

}

class GatewayActor extends Actor with ActorLogging {

  implicit def system = context.system

  private[this] val userActor = context.actorOf(UserActor.props())

  private[this] val rooms = mutable.Map.empty[RoomId, ActorRef]

  def receive: Receive = {
    case req: SubscribeRequest => req.target match {
      case id: RoomId =>
        val room = rooms.get(id).getOrElse {
          log.info("Creating a room actor: {}", id.name)
          val room = context.actorOf(RoomActor.props(id))
          rooms += id -> room
          context watch room
          room
        }
        room forward req
      case _ => // ignore
    }
    case req: Request => req.target match {
      case id: RoomId => rooms.get(id).foreach(_ forward req)
      case UserId(name) => userActor forward req
    }
    case stat: ClientStatus => userActor forward stat
    case Terminated(room) =>
      rooms.find(_._2 == room).foreach { case (roomId, _) =>
        rooms -= roomId
        log.info("A room actor was terminated: {}", roomId.name)
      }
  }

}
