name := """server-writer-reader"""

version := "1.0"

scalaVersion := "2.11.8"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.4.2",
  "com.typesafe.akka" %% "akka-remote" % "2.4.2",
  "com.typesafe.akka" %% "akka-persistence" % "2.4.2",
  "org.iq80.leveldb"  % "leveldb" % "0.7",
  "org.fusesource.leveldbjni" % "leveldbjni-all" % "1.8",
  "com.typesafe.akka" %% "akka-testkit" % "2.4.2" % "test",
  "org.scalatest" %% "scalatest" % "2.2.4" % "test")
  
fork := true
cancelable in Global := true

enablePlugins(JavaAppPackaging)
enablePlugins(DockerPlugin)


