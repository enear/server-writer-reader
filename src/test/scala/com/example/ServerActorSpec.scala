package com.example

import java.util.UUID

import akka.actor._
import akka.testkit._
import com.example.ReaderActor.SequenceUpdate
import com.example.ServerActor._
import com.example.WriterActor.RequestData
import scala.concurrent.duration._
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
  writerActor.setAutoPilot(new TestActor.AutoPilot {
    def run(sender: ActorRef, msg: Any): TestActor.AutoPilot =
      msg match {
        case RequestData(offset, length) ⇒ {
          (1 to length).foldLeft(offset) { (accum, next) =>
            sender ! WriterData(accum)
            accum + 1
          }
          TestActor.KeepRunning
        }
      }
  })
 
  "A Server actor" must {
    var serverActor = system.actorOf(ServerActor.props)

    "receive greets" in {
      writerActor.send(serverActor, WriterGreet)
    }

    "start a sequence counting correctly" in {
      val id1 = UUID.randomUUID()
      val id2 = UUID.randomUUID()
      readerActor.send(serverActor, ReaderRequest(id1, 0))
      (1 to 10) map { i => 
        readerActor.expectMsg(SequenceUpdate(id1,i))
        readerActor.send(serverActor, Acknowledge(id1))
      }
      readerActor.expectMsg(SequenceUpdate(id1, -1))
      readerActor.send(serverActor, RemoveId(id1))
      
      readerActor.send(serverActor, ReaderRequest(id2, 0))
      (11 to 20) map { i => 
        readerActor.expectMsg(SequenceUpdate(id2,i))
        readerActor.send(serverActor, Acknowledge(id2))
      }
      readerActor.expectMsg(SequenceUpdate(id2, -1))
      readerActor.send(serverActor, RemoveId(id2))

      readerActor.expectNoMsg()
    }
    
    "handle restarts" in {
      val id3 = UUID.randomUUID()
      readerActor.send(serverActor, ReaderRequest(id3, 0))
      
      serverActor ! Kill
      
      serverActor = system.actorOf(ServerActor.props)
      writerActor.send(serverActor, WriterGreet)
      
      val id4 = UUID.randomUUID()
      readerActor.send(serverActor, ReaderRequest(id4, 0))
      (21 to 30) map { i => 
        readerActor.expectMsg(SequenceUpdate(id3,i))
        readerActor.send(serverActor, Acknowledge(id3))
      }
      readerActor.expectMsg(SequenceUpdate(id3, -1))
      readerActor.send(serverActor, RemoveId(id3))
      
      (31 to 40) map { i => 
        readerActor.expectMsg(SequenceUpdate(id4,i))
        readerActor.send(serverActor, Acknowledge(id4))
      }
      readerActor.expectMsg(SequenceUpdate(id4, -1))
      readerActor.send(serverActor, RemoveId(id4))

    }

  }
}
