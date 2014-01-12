organization  := "sircuit"

name := "sircuit-server"

version       := "0.1.0"

scalaVersion  := "2.10.3"

scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8")

resolvers ++= Seq(
  "spray repo" at "http://repo.spray.io/"
)

libraryDependencies ++= {
  val akkaVersion = "2.2.3"
  val sprayVersion = "1.2.0"
  Seq(
    "com.typesafe.akka"   %%  "akka-actor"    % akkaVersion,
    "com.typesafe.akka"   %%  "akka-testkit"  % akkaVersion,
    "io.spray"            %   "spray-can"     % sprayVersion,
    "io.spray"            %   "spray-routing" % sprayVersion,
    "io.spray"            %   "spray-testkit" % sprayVersion,
    "org.scalatest"       %%  "scalatest"     % "2.0" % "test"
  )
}

seq(Revolver.settings: _*)
