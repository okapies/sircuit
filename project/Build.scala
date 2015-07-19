import sbt._
import Keys._

object SircuitBuild extends Build {

  // sbt-assembly plugin
  import sbtassembly.AssemblyPlugin.autoImport._

  val akkaVersion        = "2.3.12"
  val akkaStreamsVersion = "1.0"
  val akkaHttpVersion    = "1.0"

  lazy val sircuitServer = (project in file(".")).
    settings(
      organization  := "sircuit",
      name          := "sircuit-server",
      version       := "0.2.0",
      scalaVersion  := "2.11.7",
      scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8"),
      resolvers ++= Seq(
        "Typesafe repository" at "http://repo.typesafe.com/typesafe/releases/"
      ),
      libraryDependencies ++= {
        Seq(
          "com.typesafe.akka"  %% "akka-actor"     % akkaVersion,
          "com.typesafe.akka"  %% "akka-testkit"   % akkaVersion % "test",
          "com.typesafe.akka"  %% "akka-stream-experimental"    % akkaStreamsVersion,
          "com.typesafe.akka"  %% "akka-http-core-experimental" % akkaHttpVersion,
          "com.typesafe.akka"  %% "akka-http-experimental"      % akkaHttpVersion,
          "com.typesafe.slick" %% "slick"          % "3.0.0",
          "com.h2database"     %  "h2"             % "1.4.187",
          "org.scalatest"      %% "scalatest"      % "2.2.4" % "test"
        )
      },
      mainClass in assembly := Some("okapies.sircuit.Boot")
    )

}
