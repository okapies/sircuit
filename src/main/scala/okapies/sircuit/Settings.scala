package okapies.sircuit

import com.typesafe.config.Config

import akka.actor.ActorSystem
import akka.actor.Extension
import akka.actor.ExtensionId
import akka.actor.ExtensionIdProvider
import akka.actor.ExtendedActorSystem

class SettingsImpl(config: Config) extends Extension {

  val IrcHostname = config.getString("sircuit.irc.hostname")

  val IrcPort = config.getInt("sircuit.irc.port")

  val IrcCharset = config.getString("sircuit.irc.charset")

}

object Settings extends ExtensionId[SettingsImpl] with ExtensionIdProvider {
 
  override def lookup = Settings
 
  override def createExtension(system: ExtendedActorSystem) =
    new SettingsImpl(system.settings.config)
 
  /**
   * Java API: retrieve the Settings extension for the given system.
   */
  override def get(system: ActorSystem): SettingsImpl = super.get(system)

}
