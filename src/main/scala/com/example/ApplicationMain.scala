package com.example

import akka.actor.ActorSystem

object ServerMain extends App {
  val system = ActorSystem()
  val serverActor = system.actorOf(ServerActor.props, "serverActor")
}

object ReaderMain extends App {
  val system = ActorSystem()
  val readerActor = system.actorOf(ReaderActor.props(???), "readerActor")
}

object WriterMain extends App {
  val system = ActorSystem()
  val writerActor = system.actorOf(WriterActor.props(???), "writerActor")
}