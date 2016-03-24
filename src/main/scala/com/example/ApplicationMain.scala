package com.example

import akka.actor.{ActorSystem,Identify}
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
    val system = ActorSystem("WriterSystem", ConfigFactory.load("writer"))
    val serverSelection = system.actorSelection("akka.tcp://ServerSystem@127.0.0.1:2552/user/serverActor")
    val writerActor = system.actorOf(WriterActor.props(serverSelection), "writerActor")
  }
  
  def startReaderSystem() = {
    val system = ActorSystem("ReaderSystem", ConfigFactory.load("reader"))
    val serverSelection = system.actorSelection("akka.tcp://ServerSystem@127.0.0.1:2552/user/serverActor")
    val readerActor = system.actorOf(ReaderActor.props(serverSelection), "readerActor")
  }
}