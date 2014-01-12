package okapies.sircuit

import org.scalatest.{FlatSpec, Matchers}

import spray.testkit.ScalatestRouteTest
import spray.http._
import StatusCodes._

class SircuitServiceSpec extends FlatSpec with Matchers with ScalatestRouteTest with SircuitService {

  def actorRefFactory = system
  
  behavior of "SircuitService"

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

}
