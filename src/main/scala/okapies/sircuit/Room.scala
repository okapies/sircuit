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
      if (!members.contains(req.sender)) {
        members += req.sender -> Member(req.user)
        context watch req.sender

        val uniqueMemberIds = members.values.map(_.user).toSet
        req.sender ! SubscribeResponse(roomId, uniqueMemberIds, topic)
        val ad = ClientSubscribed(roomId, req.user)
        val isAdvertise = uniqueMemberIds.contains(req.user)
        if (isAdvertise) {
          members.keys.filter(_ != req.sender).foreach(_ ! ad)
        }
      }
    case req: UnsubscribeRequest =>
      if (members.contains(req.sender)) {
        members -= req.sender
        context unwatch req.sender

        val ad = ClientUnsubscribed(roomId, req.user, req.message)
        req.sender ! ad
        val isAdvertise = members.filter(_._2.user == req.user).isEmpty
        if (isAdvertise) {
          members.keys.foreach(_ ! ad)
        }
      } else {
        // NOTE: Send "no such room" instead of "not on the channel"
        // not to expose what room exists.
        req.sender ! NoSuchRoomError(roomId)
      }
      terminateIfNoMembers()
    case Terminated(listener) =>
      if (members.contains(listener)) {
        val member = members.get(listener)
        members -= listener

        member.foreach { member =>
          val ad = ClientUnsubscribed(roomId, member.user, "Connection reset by peer")
          val isAdvertise = members.filter(_._2.user == member.user).isEmpty
          if (isAdvertise) {
            members.keys.foreach(_ ! ad)
          }
        }
      }
      terminateIfNoMembers()
  }

  private[this] def terminateIfNoMembers() =
    if (members.isEmpty) {
      context.stop(self)
    }

}
