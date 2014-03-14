import sbt._
import Keys._

object SircuitBuild extends Build {

  // sbt-revolver plugin
  import spray.revolver.RevolverPlugin._

  // sbt-assembly plugin
  import sbtassembly.Plugin._
  import AssemblyKeys._

  // sbt-atmos for Typesafe Console
  import com.typesafe.sbt.SbtAtmos.{Atmos, atmosSettings, traceAkka}

  val akkaVersion = "2.2.4"
  val sprayVersion = "1.2.0"

  lazy val root = Project(id = "sircuit-server", base = file("."))
    .dependsOn(uri("https://github.com/okapies/SprayWebSockets.git#2a68215"))
    .settings(Project.defaultSettings: _*)
    .settings(Revolver.settings: _*)
    .settings(assemblySettings: _*)
    .settings(
      organization  := "sircuit",
      name          := "sircuit-server",
      version       := "0.1.0",
      scalaVersion  := "2.10.3",
      scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8"),
      resolvers ++= Seq(
        "Typesafe repository" at "http://repo.typesafe.com/typesafe/releases/",
        "spray repo" at "http://repo.spray.io/"
      ),
      libraryDependencies ++= {
        Seq(
          "com.typesafe.akka"   %%  "akka-actor"    % akkaVersion,
          "com.typesafe.akka"   %%  "akka-testkit"  % akkaVersion,
          "io.spray"            %   "spray-can"     % sprayVersion,
          "io.spray"            %   "spray-routing" % sprayVersion,
          "io.spray"            %   "spray-testkit" % sprayVersion,
          "org.scalatest"       %%  "scalatest"     % "2.0" % "test"
        )
      },
      mainClass in assembly := Some("okapies.sircuit.Boot"),
      traceAkka(akkaVersion)
    )
    .configs(Atmos)
    .settings(atmosSettings: _*)

}
