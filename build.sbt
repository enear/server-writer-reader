name := """server-writer-reader"""

version := "1.0"

scalaVersion := "2.11.8"

resolvers ++= Seq(
  "kamon" at "http://snapshots.kamon.io/"
)

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.4.2",
  "com.typesafe.akka" %% "akka-remote" % "2.4.2",
  
  "com.typesafe.akka" %% "akka-persistence" % "2.4.2",
  "org.iq80.leveldb"  % "leveldb" % "0.7",
  "org.fusesource.leveldbjni" % "leveldbjni-all" % "1.8",
  
  "com.github.romix.akka" %% "akka-kryo-serialization" % "0.4.0",
  
  "com.typesafe.akka" %% "akka-slf4j" % "2.4.2",
  "ch.qos.logback" % "logback-classic" % "1.1.3",
  
  "io.kamon" %% "kamon-akka" % "0.6.0-affe465fdcc002fb12c54b3bb139ba3ef4fb1d85",
  //"io.kamon" %% "kamon-akka-remote" % "0.6.0-affe465fdcc002fb12c54b3bb139ba3ef4fb1d85",
  "io.kamon" %% "kamon-autoweave" % "0.6.0-affe465fdcc002fb12c54b3bb139ba3ef4fb1d85",
  "io.kamon" %% "kamon-jmx" % "0.6.0-affe465fdcc002fb12c54b3bb139ba3ef4fb1d85",
  //"io.kamon" %% "kamon-log-reporter" % "0.6.0-affe465fdcc002fb12c54b3bb139ba3ef4fb1d85",
  "io.kamon" %% "kamon-statsd" % "0.6.0-affe465fdcc002fb12c54b3bb139ba3ef4fb1d85",
  
  "com.typesafe.akka" %% "akka-testkit" % "2.4.2" % "test",
  "org.scalatest" %% "scalatest" % "2.2.4" % "test")
  
fork := true
cancelable in Global := true

enablePlugins(JavaAppPackaging)
enablePlugins(DockerPlugin)

dockerExposedVolumes in Docker := Seq("/opt/docker/journal")
dockerExposedPorts in Docker := Seq(2552)


