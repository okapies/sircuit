package okapies.sircuit

import com.typesafe.config.{Config, ConfigException}

import akka.actor.ActorSystem
import akka.actor.Extension
import akka.actor.ExtensionId
import akka.actor.ExtensionIdProvider
import akka.actor.ExtendedActorSystem

class SettingsImpl(config: Config) extends Extension {

  import Settings._

  import scala.concurrent.duration._

  val IrcBindAddress = config.getStringOption("sircuit.api.irc.bind.address")

  val IrcBindPort = config.getInt("sircuit.api.irc.bind.port")

  val IrcCharset = config.getString("sircuit.api.irc.charset")

  val IrcConnectTimeout = config.getDuration("sircuit.api.irc.connect.timeout-sec", SECONDS)

  val IrcConnectPingFrequency = config.getDuration("sircuit.api.irc.connect.ping-freq-sec", SECONDS)

  val IrcServername = config.getString("sircuit.api.irc.servername")

}

object Settings extends ExtensionId[SettingsImpl] with ExtensionIdProvider {

  import scala.concurrent.duration._

  override def lookup = Settings
 
  override def createExtension(system: ExtendedActorSystem) =
    new SettingsImpl(system.settings.config)
 
  /**
   * Java API: retrieve the Settings extension for the given system.
   */
  override def get(system: ActorSystem): SettingsImpl = super.get(system)

  private[sircuit] implicit class ConfigOps(val underlying: Config) extends AnyVal {

    def getDuration(path: String, timeunit: TimeUnit): FiniteDuration = {
      val length = underlying.getLong(path)
      if (length > 0) {
        FiniteDuration(length, timeunit)
      } else {
        FiniteDuration(Long.MaxValue, NANOSECONDS)
      }
    }

    def getStringOption(path: String) = toOption[String](underlying.getString(path))

    private def toOption[A](v: => A): Option[A] =
      try {
        Option(v)
      } catch {
        case e: ConfigException.Missing => None
      }

  }

}
