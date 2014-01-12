package okapies.sircuit

import akka.actor.{ActorSystem, Props}
import akka.io.IO
import spray.can.Http

object Boot extends App {

  // we need an ActorSystem to host our application in
  implicit val system = ActorSystem("sircuit")

  private[this] val settings = Settings(system)

  // Http
  val httpService = system.actorOf(Props[SircuitServiceActor], "http-service")

  IO(Http) ! Http.Bind(httpService, interface = "localhost", port = 8080)

}
