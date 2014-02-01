package okapies.sircuit

import scala.collection.immutable
import scala.collection.mutable

import akka.actor._

object RoomActor {

  def props(id: RoomId) = Props(classOf[RoomActor], id)

}

class RoomActor(roomId: RoomId) extends Actor with ActorLogging {

  private[this] val members = mutable.Map.empty[UserId, immutable.Set[ActorRef]]

  private[this] var topic: Option[String] = None

  private[this] def clients: Seq[ActorRef] = members.values.flatten.toSeq

  def receive: Receive = {
    case req: MessageRequest =>
      val message = Message(req.origin, roomId, req.message)
      clients.filter(_ != req.sender).foreach(_ ! message)
    case req: NotificationRequest =>
      val notification = Notification(req.origin, roomId, req.message)
      clients.filter(_ != req.sender).foreach(_ ! notification)
    case req: UpdateTopicRequest =>
      // TODO: authorization required
      topic = req.topic
      clients.foreach(_ ! TopicStatus(roomId, req.user, req.topic))
    case req: UserInfoRequest =>
      req.sender ! RoomMembers(roomId, members.keys.map(userId => UserInfo(userId)).toSet)
    case req: SubscribeRequest =>
      val sender = req.sender
      val user = req.user
      if (!clients.contains(sender)) {
        val prevUniqueMembers = members.keys.toSet
        val isAdvertise = !prevUniqueMembers.contains(user)

        addClient(user, sender)
        context watch sender

        sender ! SubscribeResponse(roomId, prevUniqueMembers + user, topic)
        if (isAdvertise) {
          val ad = ClientSubscribed(roomId, user)
          clients.filter(_ != sender).foreach(_ ! ad)
        }
      }
    case req: UnsubscribeRequest =>
      val sender = req.sender
      val user = req.user
      if (clients.contains(sender)) {
        removeClient(user, sender)
        context unwatch sender

        sender ! UnsubscribeResponse(roomId, req.message)
        val ad = ClientUnsubscribed(roomId, user, req.message)
        val isAdvertise = !members.contains(user)
        if (isAdvertise) {
          clients.filter(_ != sender).foreach(_ ! ad)
        }
      } else {
        // NOTE: Send "no such room" instead of "not on the channel"
        // not to expose what room exists.
        sender ! NoSuchRoomError(roomId)
      }
      terminateIfNoMembers()
    case Terminated(listener) =>
      val users = members.filter(_._2.contains(listener))
      users.foreach { case (user, _) => // users.size should be 1
        removeClient(user, listener)
        val isAdvertise = !members.contains(user)
        if (isAdvertise) {
          val ad = ClientUnsubscribed(roomId, user, "Connection reset by peer")
          clients.foreach(_ ! ad)
        }
      }
      terminateIfNoMembers()
  }

  private[this] def addClient(user: UserId, client: ActorRef) = {
    val clients = members.get(user).getOrElse(Set.empty)
    members.put(user, clients + sender)
  }

  private[this] def removeClient(user: UserId, client: ActorRef) = {
    val clients = members.get(user).getOrElse(Set.empty)
    clients - client match {
      case cs if cs.isEmpty => members.remove(user)
      case cs => members.put(user, cs)
    }
  }

  private[this] def terminateIfNoMembers() =
    if (members.isEmpty) {
      context.stop(self)
    }

}
