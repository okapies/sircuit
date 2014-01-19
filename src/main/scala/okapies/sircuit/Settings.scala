package okapies.sircuit

import com.typesafe.config.Config

import akka.actor.ActorSystem
import akka.actor.Extension
import akka.actor.ExtensionId
import akka.actor.ExtensionIdProvider
import akka.actor.ExtendedActorSystem

class SettingsImpl(config: Config) extends Extension {

  import Settings._

  import scala.concurrent.duration._

  val IrcHostname = config.getString("sircuit.irc.hostname")

  val IrcPort = config.getInt("sircuit.irc.port")

  val IrcCharset = config.getString("sircuit.irc.charset")

  val IrcConnectTimeout =
    getDuration(config.getLong("sircuit.irc.connect.timeout-sec"), SECONDS)

  val IrcConnectPingFrequency =
    getDuration(config.getLong("sircuit.irc.connect.ping-freq-sec"), SECONDS)

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

  private[sircuit] def getDuration(length: Long, timeunit: TimeUnit): FiniteDuration =
    if (length > 0) {
      FiniteDuration(length, timeunit)
    } else {
      FiniteDuration(Long.MaxValue, NANOSECONDS)
    }

}
