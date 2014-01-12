package okapies.sircuit

import java.net.InetSocketAddress

import akka.actor.{ActorSystem, Props}
import akka.io.IO
import spray.can.Http

import irc.IrcServiceActor

object Boot extends App {

  // we need an ActorSystem to host our application in
  implicit val system = ActorSystem("sircuit")

  private[this] val settings = Settings(system)

  // Http
  val httpService = system.actorOf(Props[SircuitServiceActor], "http-service")

  IO(Http) ! Http.Bind(httpService, interface = "localhost", port = 8080)

  // IRC
  val ircEndpoint = new InetSocketAddress(settings.IrcHostname, settings.IrcPort)
  val ircService = system.actorOf(Props(classOf[IrcServiceActor], ircEndpoint), "IO-IRC")

}
