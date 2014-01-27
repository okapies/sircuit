package okapies.sircuit.api.irc

import org.scalatest.{Matchers, FlatSpec}

class ProtocolSpec extends FlatSpec with Matchers {

  behavior of "IrcMessage"

  it should "parse valid message lines" in {
    IrcMessage(":Name COMMAND") shouldBe Some(IrcMessage(Some("Name"), "COMMAND", Nil))

    IrcMessage("COMMAND param1") shouldBe Some(IrcMessage(None, "COMMAND", Seq("param1")))
    IrcMessage(":Name COMMAND param1") shouldBe Some(IrcMessage(Some("Name"), "COMMAND", Seq("param1")))
    IrcMessage("COMMAND param1 param2") shouldBe Some(IrcMessage(None, "COMMAND", Seq("param1", "param2")))

    IrcMessage("COMMAND :Hello, world!") shouldBe Some(IrcMessage(None, "COMMAND", Seq("Hello, world!")))
    IrcMessage("COMMAND param1 :Hello, world!") shouldBe
      Some(IrcMessage(None, "COMMAND", Seq("param1", "Hello, world!")))
    IrcMessage("COMMAND param1 param2 :Hello, world!") shouldBe
      Some(IrcMessage(None, "COMMAND", Seq("param1", "param2", "Hello, world!")))
  }

  it should "fail when message lines are invalid" in {
    IrcMessage("") shouldBe None
    IrcMessage(":Name") shouldBe None
  }

}
