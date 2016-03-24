package com.example

import akka.actor.{ActorSystem,Props}
import akka.pattern.{Backoff,BackoffSupervisor}
import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory
import kamon.Kamon

object Main {
  def main(args: Array[String]): Unit = {
	  Kamon.start()
    if (args.isEmpty || args.head == "Server")
      startServerSystem()
    if (args.isEmpty || args.head == "Writer")
      startWriterSystem()
    if (args.isEmpty || args.head == "Reader")
      startReaderSystem()
  }
  
  def startServerSystem() = {
    val system = ActorSystem("ServerSystem", ConfigFactory.load("server"))
    val serverActor = system.actorOf(ServerActor.props, "serverActor")
  }
  
  def startWriterSystem() = {
    val config = ConfigFactory.load("writer")
    val serverHost = config.getString("serverHost")
    val serverPort = config.getInt("serverPort")
    val system = ActorSystem("WriterSystem", config)
    val serverSelection = system.actorSelection(s"akka.tcp://ServerSystem@$serverHost:$serverPort/user/serverActor")
    val writerActor = supervise(system, WriterActor.props(serverSelection), "writerActor")
  }
  
  def startReaderSystem() = {
    val config = ConfigFactory.load("reader")
    val serverHost = config.getString("serverHost")
    val serverPort = config.getInt("serverPort")
    val system = ActorSystem("ReaderSystem", config)
    val serverSelection = system.actorSelection(s"akka.tcp://ServerSystem@$serverHost:$serverPort/user/serverActor")
    val readerActor = supervise(system, ReaderActor.props(serverSelection), "readerActor")
  }
  
  def supervise(system: ActorSystem, childProps: Props, childName: String) = {
    val supervisor = BackoffSupervisor.props(
      Backoff.onStop(
        childProps,
        childName = childName,
        minBackoff = 3 seconds,
        maxBackoff = 30 seconds,
        randomFactor = 0.2 // adds 20% "noise" to vary the intervals slightly
      ))
    system.actorOf(supervisor, name = "supervisor")

  }
}