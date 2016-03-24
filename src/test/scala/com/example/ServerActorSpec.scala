package com.example

import java.util.UUID

import akka.actor.ActorSystem
import com.example.ReaderActor.SequenceUpdate
import com.example.WriterActor.RequestData
import scala.concurrent.duration._
import akka.testkit._
import com.example.ServerActor.{RemoveId, WriterGreet, ReaderRequest}
import org.scalatest.WordSpecLike
import org.scalatest.Matchers
import org.scalatest.BeforeAndAfterAll
 
class ServerActorSpec extends TestKit(ActorSystem("ServerActorSpec")) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll {

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  val readerActor = TestProbe()
  val writerActor = TestProbe()
 
  "A Server actor" must {
    val serverActor = TestActorRef[ServerActor](ServerActor.props)

    "receive greets" in {
      writerActor.send(serverActor, WriterGreet)
      Thread.sleep(1000)
      serverActor.underlyingActor.writerActor shouldEqual Some(writerActor.ref)
    }

    "start a sequence counting correctly" in {
      val id1 = UUID.randomUUID()
      val id2 = UUID.randomUUID()
      readerActor.send(serverActor, ReaderRequest(id1, 0))
      readerActor.send(serverActor, ReaderRequest(id2, 0))
      writerActor.expectMsg(500.millis, RequestData(0, 10))
      (0 to 9) foreach(writerActor.send(serverActor, _))
      val updates = readerActor.receiveN(9) map (_.asInstanceOf[SequenceUpdate])
      updates map(_.count) shouldBe sorted
      readerActor.expectMsg(500.millis, SequenceUpdate(id1, -1))
      readerActor.send(serverActor, RemoveId(id1))
      serverActor.underlyingActor.currentState shouldEqual Some(id2, 0)
    }
  }
}
