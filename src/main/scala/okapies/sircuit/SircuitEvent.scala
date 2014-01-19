package okapies.sircuit

import akka.actor.ActorRef

sealed trait Identifier

case class UserId(name: String) extends Identifier

case class RoomId(name: String) extends Identifier

trait SircuitEvent

trait Request extends SircuitEvent {
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

trait Response extends SircuitEvent

case class SubscribeResponse(
  room: RoomId,
  members: Set[UserId],
  topic: Option[String]) extends Response

case class UnsubscribeResponse(
  room: RoomId,
  message: String) extends Response

trait ErrorResponse extends Response

case class NoSuchRoomError(room: RoomId) extends ErrorResponse

trait Advertisement extends SircuitEvent

case class Message(
  origin: UserId,
  target: Identifier,
  message: String) extends Advertisement

case class Notification(
  origin: UserId,
  target: Identifier,
  message: String) extends Advertisement

case class ClientSubscribed(
  room: RoomId,
  user: UserId) extends Advertisement

case class ClientUnsubscribed(
  room: RoomId,
  user: UserId,
  message: String) extends Advertisement

case class TopicUpdated(
  room: RoomId,
  user: UserId,
  topic: Option[String]
) extends Advertisement

case class UserInfo(id: UserId)

case class RoomMembers(
  room: RoomId,
  members: Seq[UserInfo]) extends Advertisement
