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

  var readerActor = createReaderProbe()
  var writerActor = createWriterProbe()
 
  "A Server actor" must {
    var serverActor = system.actorOf(ServerActor.props, "server")

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
      
      //receive remaining messages
      val updates3 = readerActor.receiveWhile(){
        case su@SequenceUpdate(`id3`, i) if i > 0 => readerActor.send(serverActor, Acknowledge(id3)); su
      }
      updates3.map(_.count) shouldBe sorted
      readerActor.expectNoMsg()

      //restart
      serverActor = system.actorOf(ServerActor.props, "serverRestarted")
      writerActor.send(serverActor, WriterGreet)
      
      val id4 = UUID.randomUUID()
      readerActor.send(serverActor, ReaderRequest(id4, 0))
      
      //receive for id4
      (21 to 30) map { i => 
        readerActor.expectMsg(SequenceUpdate(id4,i))
        readerActor.send(serverActor, Acknowledge(id4))
      }
      readerActor.expectMsg(SequenceUpdate(id4, -1))
      readerActor.send(serverActor, RemoveId(id4))
      
      readerActor.expectNoMsg()

    }
    
    "handle writer restarts" in {
      writerActor.ref ! Kill
      
      writerActor = createWriterProbe()
      writerActor.send(serverActor, WriterGreet)
      
      val id5 = UUID.randomUUID()
      readerActor.send(serverActor, ReaderRequest(id5, 0))
      (31 to 40) map { i => 
        readerActor.expectMsg(SequenceUpdate(id5,i))
        readerActor.send(serverActor, Acknowledge(id5))
      }
      readerActor.expectMsg(SequenceUpdate(id5, -1))
      readerActor.send(serverActor, RemoveId(id5))
      
      readerActor.expectNoMsg()
    }
    
    "handle reader restarts" in {
      val id6 = UUID.randomUUID()
      readerActor.send(serverActor, ReaderRequest(id6, 0))
      
      readerActor.ref ! Kill
      
      readerActor = createReaderProbe()
      
      val id7 = UUID.randomUUID()
      readerActor.send(serverActor, ReaderRequest(id7, 0))
      (41 to 50) map { i => 
        readerActor.expectMsg(SequenceUpdate(id7,i))
        readerActor.send(serverActor, Acknowledge(id7))
      }
      readerActor.expectMsg(SequenceUpdate(id7, -1))
      readerActor.send(serverActor, RemoveId(id7))
    }
  }
  
  def createReaderProbe() = {
    TestProbe("reader")
  }
  
  def createWriterProbe() = {
    val probe = TestProbe("writer")
    probe.setAutoPilot(new TestActor.AutoPilot {
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
    probe
  }
}
