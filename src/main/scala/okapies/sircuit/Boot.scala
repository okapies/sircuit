package okapies.sircuit

import akka.actor.ActorSystem
import akka.io.IO
import spray.can.Http
import spray.can.server.websockets.Sockets

import api.http.RestInterfaceActor
import api.irc.IrcInterfaceActor

object Boot extends App {

  // we need an ActorSystem to host our application in
  implicit val system = ActorSystem("sircuit")

  // Gateway service
  val gateway = system.actorOf(GatewayActor.props(), "service")

  // REST interface
  val restInterface = system.actorOf(RestInterfaceActor.props(gateway), "rest-api")

  IO(Sockets) ! Http.Bind(restInterface, interface = "0.0.0.0", port = 8080)

  // IRC interface
  val ircInterface = system.actorOf(IrcInterfaceActor.props(gateway), "irc-api")

}
