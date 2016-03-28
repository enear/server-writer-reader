package com.example

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import com.example.ReaderActor._
import com.example.ServerActor.ReaderRequest
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

class ReadActorSpec extends TestKit(ActorSystem("ReaderActorSpec")) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll {

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  val serverActor = TestProbe()

  "A Read Actor " must {
    val testReadActor = TestActorRef[ReaderActor](ReaderActor.props(system.actorSelection(serverActor.ref.path), 10))
    var messages = Seq[ReaderRequest]()

    "send 10 new UUIDs to the server" in {
      messages = serverActor receiveN 10 map (_.asInstanceOf[ReaderRequest])
      messages foreach { msg =>
        msg.i shouldEqual 0
      }
    }

    "Correctly update the state of a UUID sequence" in {
      val subsetId = messages take 1 map (_.uuid)

      for {
        id <- subsetId
        update <- 1 to 10
      } testReadActor ! SequenceUpdate(id, update)

      val actorState = testReadActor.underlyingActor.idMap
      subsetId foreach { id =>
        actorState.get(id) shouldEqual Some(10)
      }
    }

    "Correctly remove the state of its UUID sequences and start new ones" in {
      val subsetIds = messages take 5 map (_.uuid)

      for (id <- subsetIds) testReadActor ! SequenceUpdate(id, -1)

      val actorState = testReadActor.underlyingActor.idMap
      actorState.size shouldEqual 10

      subsetIds foreach { id =>
        actorState.get(id) shouldEqual None
      }
    }
  }
}
