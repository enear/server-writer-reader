package com.example

import akka.actor.ActorSystem
import akka.testkit.{TestActorRef, TestProbe, ImplicitSender, TestKit}
import com.example.ReaderActor.SequenceUpdate
import com.example.ServerActor.ReaderRequest
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.collection.mutable

/**
  * Created by luis on 23-03-2016.
  */
class ReadActorSpec extends TestKit(ActorSystem("ReaderActorSpec")) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll {

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  val serverActor = TestProbe()

  "A Read Actor " must {
    val testReadActor = TestActorRef[ReaderActor](ReaderActor.props(system.actorSelection(serverActor.ref.path)))
    var messages = Seq[ReaderRequest]()

    "send 1000 new UUIDs to the server" in {
      messages = serverActor receiveN 1000 map (_.asInstanceOf[ReaderRequest])
      messages foreach { msg =>
        msg.i shouldEqual 0
      }
    }

    "Correctly update the state of its UUID sequences" in {
      val subsetIds = messages take 10 map (_.uuid)

      for {
        id <- subsetIds
        update <- 0 to 9
      } testReadActor ! SequenceUpdate(id, update)

      val actorState = testReadActor.underlyingActor.idMap
      subsetIds foreach { id =>
        actorState.get(id) shouldEqual Some(9)
      }
    }

    "Correctly remove the state of its UUID sequences and start new ones" in {
      val subsetIds = messages take 10 map (_.uuid)

      for (id <- subsetIds) testReadActor ! SequenceUpdate(id, -1)

      val actorState = testReadActor.underlyingActor.idMap
      actorState.size shouldEqual 1000

      subsetIds foreach { id =>
        actorState.get(id) shouldEqual None
      }
    }
  }
}
