package com.example

import akka.actor.{Actor, ActorRef, ActorLogging, Props}
import akka.persistence._
import java.util.UUID

import scala.collection.mutable

class ServerActor extends PersistentActor with ActorLogging {
  import ServerActor._
  
  override def persistenceId = "sample-id-1"
  
  def updateState(evt: Evt):Unit = {
    
  }
  
  val receiveRecover: Receive = {
    case evt: Evt => updateState(evt)
//    case SnapshotOffer(_, snapshot) => () //state = snapshot
  }
 
  val receiveCommand: Receive = {
    case WriterGreet => 
	    log.info("In ServerActor - greet")
    case WriterData(i) => 
      persist(WriterEvt(UUID.randomUUID(), i))(updateState)
    case ReaderRequest(uuid, i) => 
      persist(ReaderEvt(uuid, i))(updateState)
//    case Cmd(data) =>
//      persist(Evt(s"${data}-${numEvents}"))(updateState)
//      persist(Evt(s"${data}-${numEvents + 1}")) { event =>
//        updateState(event)
//        context.system.eventStream.publish(event)
//      }
//    case "snap"  => saveSnapshot(state)
//    case "print" => println(state)
  }
}

object ServerActor {
  val props = Props[ServerActor]
  case object WriterGreet
  case class WriterData(i: Int)
  case class ReaderRequest(uuid: UUID, i: Int)
  
  sealed trait Evt
  case class ReaderEvt(uuid: UUID, int: Int) extends Evt
  case class WriterEvt(uuid: UUID, int: Int) extends Evt
}

class WriterActor(serverActor: ActorRef) extends Actor with ActorLogging {
  import WriterActor._
  import ServerActor._

  override def preStart() = {
    log.info("Starting - greeting server actor")
    serverActor ! WriterGreet
  }

  def receive: Receive = {
    case RequestData(offset,length) =>
      log.info(s"Received a write request for offset: $offset and length: $length")

      (1 to length).foldLeft(offset) { (accum, next) =>
        serverActor ! WriterData(accum)
        accum + 1
      }
  }
}
object WriterActor {
  def props(server: ActorRef) = Props(classOf[WriterActor], server)
  case class RequestData(offset: Int, length: Int)
}

class ReaderActor(serverActor: ActorRef) extends Actor with ActorLogging {
  import ReaderActor._
  import ServerActor.ReaderRequest
  import scala.collection.mutable.HashMap

  val idMap = new HashMap[UUID, Int]

  override def preStart() = {
    log.info("Starting - creating 1000 random UUIDs")
    (1 to 1000) foreach { _ =>
      val id = UUID.randomUUID()
      idMap.put(id, 0)
      serverActor ! ReaderRequest(id, 0)
    }
  }
  
  def receive: Receive = {
    case SequenceUpdate(id, count) if count > -1 =>
      log.info(s"Received an update for id: $id and count: $count")
      idMap.put(id, count)

    case SequenceUpdate(id, count) if count == -1 =>
      log.info(s"Received a deletion for id: $id")
      idMap.remove(id)
      serverActor ! RemoveId(id)
      val newId = UUID.randomUUID()
      idMap.put(newId, 0)
      serverActor ! ReaderRequest(id, 0)
  }
}

object ReaderActor {
  def props(server: ActorRef) = Props(classOf[ReaderActor], server)
  case class SequenceUpdate(uuid: UUID, count: Int)
  case class RemoveId(uuid: UUID)
}