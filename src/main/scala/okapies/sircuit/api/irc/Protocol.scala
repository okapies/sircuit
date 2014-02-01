package okapies.sircuit.api.irc

case class IrcMessage(prefix: Option[String], command: String, params: Seq[String]) {

  def asProtocol: String =
    prefix.map(":" + _ + " ").getOrElse("") +
    command +
    params.init.map(" " + _).mkString +
    params.lastOption.map(p =>
      if (p.contains(" ") || p.startsWith(":")) {
        " :" + p
      } else {
        " " + p
      }
    ).getOrElse("")

}

object IrcMessage {

  private[this] val Prefix = "^(?:[:](\\S+) )?(.+)$".r

  private[this] val Command = "^([a-zA-Z]+|[0-9]{3}+)(.+)?$".r

  private[this] val Params = "^(?: (?!:)(.+?))?(?: [:](.*))?$".r

  def apply(line: String): Option[IrcMessage] = line match {
    case Prefix(prefix, rest) => parseCommand(Option(prefix), rest)
    case _ => None
  }

  private[this] def parseCommand(prefix: Option[String], line: String) = line match {
    case Command(command, rest) => parseParams(prefix, command, rest)
    case _ => None
  }

  private[this] def parseParams(
      prefix: Option[String], command: String, line: String) = line match {
    case null =>
      Some(IrcMessage(prefix, command, Nil))
    case Params(middlesStr, trailing) =>
      val middles = middlesStr match {
        case null => Array.empty[String]
        case _ => middlesStr.split(" ")
      }
      val params = trailing match {
        case null => middles
        case _ => middles :+ trailing
      }
      Some(IrcMessage(prefix, command, params))
    case _ => None
  }

}
