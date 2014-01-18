package okapies.sircuit

import scala.collection.mutable

import akka.actor._

object GatewayActor {

  def props() = Props(classOf[GatewayActor])

}

class GatewayActor extends Actor with ActorLogging {

  implicit def system = context.system

  private[this] val rooms = mutable.Map.empty[RoomId, ActorRef]

  def receive: Receive = {
    case msg: UnsubscribeRequest => msg.target match {
      case id: RoomId => rooms.get(id) match {
        case Some(room) => room forward msg
        case None => msg.sender ! NoSuchRoomError(id)
      }
      case _: UserId => // ignore
    }
    case msg: Request => msg.target match {
      case id: RoomId =>
        val room = rooms.get(id).getOrElse {
          log.info("Creating a room actor: {}", id.name)
          val room = context.actorOf(RoomActor.props(id))
          rooms += id -> room
          context watch room
          room
        }
        room forward msg
      case UserId(name) => // TODO
    }
    case Terminated(room) =>
      rooms.find(_._2 == room).foreach { case (roomId, _) =>
        rooms -= roomId
        log.info("A room actor was terminated: {}", roomId.name)
      }
  }

}
