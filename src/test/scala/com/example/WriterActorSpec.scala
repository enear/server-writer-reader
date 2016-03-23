package com.example

import akka.actor.ActorSystem
import akka.testkit.{TestProbe, ImplicitSender, TestKit}
import com.example.ServerActor.{WriterData, WriterGreet}
import com.example.WriterActor.RequestData
import org.scalatest.{WordSpecLike, BeforeAndAfterAll, Matchers}
import scala.concurrent.duration._

/**
  * Created by luis on 23-03-2016.
  */
class WriterActorSpec extends TestKit(ActorSystem("WriterActorSpec")) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll {

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  val serverActor = TestProbe()

  "A Writer Actor " must {
    val writerActor = system.actorOf(WriterActor.props(system.actorSelection(serverActor.ref.path)), "writerActor")
    "sends greets" in {
      serverActor.expectMsg(500.millis, WriterGreet)
    }

    "iterate from offset to length for a received write request" in {
      writerActor ! RequestData(5,3)
      serverActor.expectMsg(500.millis, WriterData(5))
      serverActor.expectMsg(500.millis, WriterData(6))
      serverActor.expectMsg(500.millis, WriterData(7))
    }

  }

}
