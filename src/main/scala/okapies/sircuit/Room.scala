package okapies.sircuit

import scala.collection.mutable

import akka.actor._

object RoomActor {

  case class Member(user: UserId)

  def props(id: RoomId) = Props(classOf[RoomActor], id)

}

class RoomActor(roomId: RoomId) extends Actor with ActorLogging {

  import RoomActor._

  private[this] val members = mutable.Map.empty[ActorRef, Member]

  private[this] val topic = Some("room's topic")

  def receive: Receive = {
    case req: MessageRequest =>
      val message = Message(req.origin, roomId, req.message)
      members.keys.filter(_ != req.sender).foreach(_ ! message)
    case req: NotificationRequest =>
      val notification = Notification(req.origin, roomId, req.message)
      members.keys.filter(_ != req.sender).foreach(_ ! notification)
    case req: SubscribeRequest =>
      members += req.sender -> Member(req.user)
      context watch req.sender
      val ad = ClientSubscribed(roomId, req.user)
      members.keys.foreach { member =>
        if (member != req.sender) {
          member ! ad
        } else {
          val uniqueMemberIds = members.values.map(_.user).toSet
          member ! SubscribeResponse(roomId, uniqueMemberIds, topic)
        }
      }
    case req: UnsubscribeRequest =>
      members -= req.sender
      context unwatch req.sender
      val ad = ClientUnsubscribed(roomId, req.user, req.message)
      req.sender ! ad
      members.keys.foreach(_ ! ad)
      terminateIfNoMembers()
    case Terminated(listener) =>
      val member = members.get(listener)
      members -= listener
      member.foreach { member =>
        val ad = ClientUnsubscribed(roomId, member.user, "Connection reset by peer")
        members.keys.foreach(_ ! ad)
      }
      terminateIfNoMembers()
  }

  private[this] def terminateIfNoMembers() =
    if (members.isEmpty) {
      context.stop(self)
    }

}
