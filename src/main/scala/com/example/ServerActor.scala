package com.example

import akka.actor._
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
      log.info("In ServerActor - WriterData")
      persist(WriterEvt(UUID.randomUUID(), i))(updateState)
    case ReaderRequest(uuid, i) => 
      log.info("In ServerActor - Reader Request")
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

class WriterActor(serverActor: ActorSelection) extends Actor with ActorLogging {
  import WriterActor._
  import ServerActor._
  
  val correlationId = UUID.randomUUID()

  override def preStart() = {
    log.info("Indentifying server")
    serverActor ! Identify(correlationId)
  }

  def receive: Receive = {
    case ActorIdentity(`correlationId`, Some(server)) =>
      log.info(s"Identified $server Greeting")
      context.watch(server)
      server ! WriterGreet
    case ActorIdentity(`correlationId`, None) => 
      log.warning("No server identified. Restarting...")
      self ! PoisonPill
    case ActorIdentity(_,_) =>
      log.warning("Server identified with wrong correlationId. Restarting...")
      self ! PoisonPill
      
    case RequestData(offset,length) =>
      log.info(s"Received a write request for offset: $offset and length: $length")

      (1 to length).foldLeft(offset) { (accum, next) =>
        sender ! WriterData(accum)
        accum + 1
      }
      case Terminated(s) =>
        log.info(s"server $s terminated. Restarting...")
        self ! PoisonPill
  }
  
}
object WriterActor {
  def props(server: ActorSelection) = Props(classOf[WriterActor], server)
  case class RequestData(offset: Int, length: Int)
}

class ReaderActor(serverActor: ActorSelection) extends Actor with ActorLogging {
  import ReaderActor._
  import ServerActor.ReaderRequest
  import scala.collection.mutable.HashMap

  val idMap = new HashMap[UUID, Int]
  val correlationId = UUID.randomUUID()

  override def preStart() = {
    log.info("Indentifying server")
    serverActor ! Identify(correlationId)
  }
  
  def receive: Receive = {
    case ActorIdentity(`correlationId`, Some(server)) =>
      log.info(s"Identified $server Starting - creating 1000 random UUIDs")
      context.watch(server)
      (1 to 1000) foreach { _ =>
        val id = UUID.randomUUID()
        idMap.put(id, 0)
        server ! ReaderRequest(id, 0)
      }
    case ActorIdentity(`correlationId`, None) => 
      log.warning("No server identified. Restarting...")
      self ! PoisonPill
    case ActorIdentity(_,_) =>
      log.warning("Server identified with wrong correlationId. Restarting...")
      self ! PoisonPill
      
    case SequenceUpdate(id, count) if count > -1 =>
      log.info(s"Received an update for id: $id and count: $count")
      idMap.put(id, count)

    case SequenceUpdate(id, count) if count == -1 =>
      log.info(s"Received a deletion for id: $id")
      idMap.remove(id)
      sender ! RemoveId(id)
      val newId = UUID.randomUUID()
      idMap.put(newId, 0)
      sender ! ReaderRequest(id, 0)
      
    case Terminated(s) =>
      log.warning(s"server $s terminated. Restarting...")
      self ! PoisonPill
  }
}

object ReaderActor {
  def props(server: ActorSelection) = Props(classOf[ReaderActor], server)
  case class SequenceUpdate(uuid: UUID, count: Int)
  case class RemoveId(uuid: UUID)
}