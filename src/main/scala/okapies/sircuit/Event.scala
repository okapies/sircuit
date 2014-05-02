package okapies.sircuit

import akka.actor.ActorRef

sealed trait Identifier

case class UserId(name: String) extends Identifier

case class ChannelId(name: String) extends Identifier

trait Event

trait Request extends Event {
  def sender: ActorRef
  def target: Identifier
}

case class MessageRequest(
  sender: ActorRef,
  target: Identifier,
  origin: UserId,
  message: String) extends Request

case class NotificationRequest(
  sender: ActorRef,
  target: Identifier,
  origin: UserId,
  message: String) extends Request

case class SubscribeRequest(
  sender: ActorRef,
  target: Identifier,
  user: UserId) extends Request

case class UnsubscribeRequest(
  sender: ActorRef,
  target: Identifier,
  user: UserId,
  message: String) extends Request

case class UpdateTopicRequest(
  sender: ActorRef,
  target: Identifier,
  user: UserId,
  topic: Option[String]) extends Request

case class UserInfoRequest(
  sender: ActorRef,
  target: Identifier) extends Request

trait Response extends Event

case class SubscribeResponse(
  sender: ActorRef,
  channel: ChannelId,
  members: Set[UserId],
  topic: Option[String]) extends Response

case class UnsubscribeResponse(
  channel: ChannelId,
  message: String) extends Response

trait ErrorResponse extends Response

case class NoSuchUserError(channel: UserId) extends ErrorResponse

case class NoSuchChannelError(channel: ChannelId) extends ErrorResponse

trait ClientStatus

case class ClientOnline(
  sender: ActorRef,
  user: UserId) extends ClientStatus

case class ClientOffline(
  sender: ActorRef,
  user: UserId) extends ClientStatus

trait Advertisement extends Event

case class Message(
  time: Long,
  origin: UserId,
  target: Identifier,
  message: String) extends Advertisement

case class Notification(
  time: Long,
  origin: UserId,
  target: Identifier,
  message: String) extends Advertisement

case class ClientSubscribed(
  time: Long,
  channel: ChannelId,
  user: UserId) extends Advertisement

case class ClientUnsubscribed(
  time: Long,
  channel: ChannelId,
  user: UserId,
  message: String) extends Advertisement

case class TopicStatus(
  time: Long,
  channel: ChannelId,
  user: UserId,
  topic: Option[String]
) extends Advertisement

case class UserInfo(id: UserId)

case class ChannelMembers(
  time: Long,
  channel: ChannelId,
  members: Set[UserInfo]) extends Advertisement
