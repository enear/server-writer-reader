package com.example

import akka.actor.ActorSystem
import akka.testkit.{TestProbe, ImplicitSender, TestKit}
import com.example.ServerActor.WriterGreet
import com.example.WriterActor.RequestData
import org.scalatest.{WordSpecLike, BeforeAndAfterAll, Matchers}
import scala.concurrent.duration._

/**
  * Created by luis on 23-03-2016.
  */
class WriterActorSpec extends TestKit(ActorSystem("WriterActorSpec")) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll {

  //def this() = this(ActorSystem("WriterActorSpec"))

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  val serverActor = TestProbe()

  "A Writer Actor " must {
    val writerActor = system.actorOf(WriterActor.props(serverActor.ref), "writerGreet")
    "sends greets" in {
      serverActor.expectMsg(500.millis, WriterGreet)
    }

    "iterate from offset to length for a received write request" in {
      writerActor ! RequestData(5,3)
      serverActor.expectMsg(500.millis, 5)
      serverActor.expectMsg(500.millis, 6)
      serverActor.expectMsg(500.millis, 7)
    }

  }

}
