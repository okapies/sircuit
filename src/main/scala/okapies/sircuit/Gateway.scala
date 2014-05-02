package okapies.sircuit

import scala.collection.mutable

import akka.actor._

object GatewayActor {

  def props() = Props(classOf[GatewayActor])

}

class GatewayActor extends Actor with ActorLogging {

  implicit def system = context.system

  private[this] val userActor = context.actorOf(UserActor.props())

  private[this] val channels = mutable.Map.empty[ChannelId, ActorRef]

  def receive: Receive = {
    case req: SubscribeRequest => req.target match {
      case id: ChannelId =>
        val channel = channels.get(id).getOrElse {
          log.info("Creating a channel actor: {}", id.name)
          val channel = context.actorOf(ChannelActor.props(id))
          channels += id -> channel
          context watch channel
          channel
        }
        channel forward req
      case _ => // ignore
    }
    case req: Request => req.target match {
      case id: ChannelId => channels.get(id).foreach(_ forward req)
      case UserId(name) => userActor forward req
    }
    case stat: ClientStatus => userActor forward stat
    case Terminated(channel) =>
      channels.find(_._2 == channel).foreach { case (channelId, _) =>
        channels -= channelId
        log.info("A channel actor was terminated: {}", channelId.name)
      }
  }

}
