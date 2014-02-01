package okapies.sircuit

import scala.collection.mutable

import akka.actor._

object UserActor {

  def props() = Props(classOf[UserActor])

}

class UserActor extends Actor with ActorLogging {

  private[this] val users = mutable.Map.empty[UserId, Seq[ActorRef]]

  def receive: Receive = {
    case ad: ClientOnline =>
      registerClient(ad.sender, ad.user)
      context watch ad.sender
    case ad: ClientOffline =>
      unregisterClient(ad.sender, ad.user)
      context unwatch ad.sender
    case req: MessageRequest =>
      req.target match {
        case user: UserId =>
          users.get(user).foreach { clients =>
            clients.foreach(_ ! Message(req.origin, user, req.message))
          }
        case _ =>
      }
    case req: NotificationRequest =>
      req.target match {
        case user: UserId =>
          users.get(user).foreach { clients =>
            clients.foreach(_ ! Notification(req.origin, user, req.message))
          }
        case _ =>
      }
    case Terminated(client) =>
      // TODO: make more efficient
      users.keys.foreach(user => unregisterClient(client, user))
      context unwatch client
  }

  private[this] def registerClient(client: ActorRef, user: UserId) = {
    val clients = users.getOrElse(user, Nil)
    users.put(user, clients :+ client)
  }

  private[this] def unregisterClient(client: ActorRef, user: UserId) = {
    val clients = users.getOrElse(user, Nil).filter(_ != client)
    if (clients.isEmpty) {
      users -= user
    } else {
      users.put(user, clients)
    }
  }

}
