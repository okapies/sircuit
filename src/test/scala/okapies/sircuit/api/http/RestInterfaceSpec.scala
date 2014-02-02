package okapies.sircuit.api.http

import org.scalatest.{FlatSpec, Matchers}

import spray.testkit.ScalatestRouteTest
import spray.http._
import StatusCodes._

import okapies.sircuit.{Identifier, UserId}

class RestInterfaceSpec extends FlatSpec with Matchers with ScalatestRouteTest with RestInterface {

  def actorRefFactory = system
  
  behavior of "SircuitService"

/*
  it should "return a greeting for GET requests to the root path" in {
    Get() ~> route ~> check {
      responseAs[String] should include("Say hello")
    }
  }

  it should "leave GET requests to other paths unhandled" in {
    Get("/kermit") ~> route ~> check {
      handled shouldBe false
    }
  }

  it should "return a MethodNotAllowed error for PUT requests to the root path" in {
    Put() ~> sealRoute(route) ~> check {
      status shouldBe MethodNotAllowed
      responseAs[String] shouldBe "HTTP method not allowed, supported methods: GET"
    }
  }
*/

  def sendMessage(target: Identifier, origin: UserId, message: String) = ???

  def sendNotification(target: Identifier, origin: UserId, message: String) = ???

}
