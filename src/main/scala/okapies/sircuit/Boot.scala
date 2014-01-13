package okapies.sircuit

import akka.actor.ActorSystem
import akka.io.IO
import spray.can.Http

import http.RestInterfaceActor
import irc.IrcInterfaceActor

object Boot extends App {

  // we need an ActorSystem to host our application in
  implicit val system = ActorSystem("sircuit")

  private[this] val settings = Settings(system)

  // REST interface
  val restInterface = system.actorOf(RestInterfaceActor.props(), "rest-interface")

  IO(Http) ! Http.Bind(restInterface, interface = "localhost", port = 8080)

  // IRC interface
  val ircInterface = system.actorOf(IrcInterfaceActor.props(), "irc-interface")

}
