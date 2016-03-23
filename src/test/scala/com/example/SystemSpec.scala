package com.example

import akka.actor.ActorSystem
import akka.actor.Actor
import akka.actor.Props
import akka.testkit.{ TestActors, TestKit, ImplicitSender }
import org.scalatest.WordSpecLike
import org.scalatest.Matchers
import org.scalatest.BeforeAndAfterAll
 
class SystemSpec(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll {
 
  def this() = this(ActorSystem("MySpec"))
 
  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }
 
  "The system" must {
    "bootstrap" in {
      val serverActor = system.actorOf(ServerActor.props, "server")
      val readerActor = system.actorOf(ReaderActor.props(serverActor), "reader")
      val writerActor = system.actorOf(WriterActor.props(serverActor), "writer")
      Thread.sleep(1000)
      system.stop(readerActor)
    }
  }
}
