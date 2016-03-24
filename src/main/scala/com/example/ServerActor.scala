package com.example

import akka.actor._
import akka.persistence._
import java.util.UUID

import scala.collection.mutable.Queue
import scala.collection.mutable.HashMap

class ServerActor extends PersistentActor with ActorLogging {
  import ServerActor._
  import WriterActor.RequestData

  override def persistenceId = "sample-id-1"
  var currentState: Option[(UUID, Int)] = None
  val idQueue = new Queue[UUID]
  var readerActor: Option[ActorRef] = None
  var writerActor: Option[ActorRef] = None

  def updateState(evt: Evt): (UUID, Int) = {
    evt match {
      case WriterEvt(id, count)  =>
        currentState = Some(id, count); currentState.get

      case ReaderEvt(id) =>
        currentState.fold { currentState = Some(id, 0) }  { _ => idQueue.enqueue(id) }; currentState.get

      case RemoveIdEvt(id) =>
        currentState = Some(idQueue.dequeue(), 0); currentState.get
    }
  }

  def printState() = {
    val entries = idQueue.length
    log.info(s"Current state contains $entries")
  }

  val receiveRecover: Receive = {
    case evt: Evt => updateState(evt)
    //    case SnapshotOffer(_, snapshot) => () //state = snapshot
  }

  val receiveCommand: Receive = {
    case WriterGreet =>
      log.debug("In ServerActor - greet")
      writerActor = Some(sender())
      unstashAll()

    case WriterData(count) =>
      currentState match {
        case Some((id, _)) =>
          log.debug("In ServerActor - WriterData")
          persist(WriterEvt(id, count)) { event =>
            updateState(event)
            readerActor
              .fold { log.error("In ServerActor - Received a write message but no reader actor is assigned") }
              { reader =>
                reader ! ReaderActor.SequenceUpdate(id, count)
                if(count == 9) reader ! ReaderActor.SequenceUpdate(id, -1)
              }
          }
        case None => log.error("In ServerActor - Received a write message but no currentId is assigned")
      }

    case ReaderRequest(uuid, count) =>
      log.debug("In ServerActor - Reader Request")

      if(readerActor.isEmpty) readerActor = Some(sender())
      val firstMessage = currentState.isEmpty
      persist(ReaderEvt(uuid)){ event =>
        val (_ , currentCount) = updateState(event)
        if(firstMessage)
          self ! WriterRequest(currentCount)
      }

    case RemoveId(id) =>
      log.debug("In ServerActor - Remove ID")
      persist(RemoveIdEvt(id)) { event =>
        updateState(event)
        self ! WriterRequest(0)
      }

    case WriterRequest(count) =>
      println("HUHUEHUEEUHEUHUEEUHUHEUHEUEH")
      writerActor match {
        case Some(writer) =>
          println("FERREIRA O TESTE DIZ QUE É NONE MAS AQUI PASSA PARA O SOME!!!!!!!!!!! QUE MERDA É ESTA!!!!!!!!!!!!!!!!!!!!! O AKKA DEVIA MORRER E O JONAS BONER SER IMPALADO!!!!!!!!!")
          writer ! RequestData(count, 10 - count)
        case None =>
          log.debug("In ServerActor - Received a read message but no writer actor is assigned. Stashing...")
          stash()
      }

    case "print" => printState()
  }
}

object ServerActor {
  val props = Props[ServerActor]
  case object WriterGreet
  case class WriterData(i: Int)
  case class ReaderRequest(uuid: UUID, i: Int)
  case class RemoveId(uuid: UUID)
  case class WriterRequest(count: Int)

  sealed trait Evt
  case class ReaderEvt(uuid: UUID) extends Evt
  case class WriterEvt(uuid: UUID, count: Int) extends Evt
  case class RemoveIdEvt(uuid: UUID) extends Evt
}

class WriterActor(serverActor: ActorSelection) extends Actor with ActorLogging {
  import WriterActor._
  import ServerActor._

  val correlationId = UUID.randomUUID()

  override def preStart() = {
    log.debug("Indentifying server")
    serverActor ! Identify(correlationId)
  }

  def receive: Receive = {
    case ActorIdentity(`correlationId`, Some(server)) =>
      log.debug(s"Identified $server Greeting")
      context.watch(server)
      server ! WriterGreet
    case ActorIdentity(`correlationId`, None) =>
      log.warning("No server identified. Restarting...")
      self ! PoisonPill
    case ActorIdentity(_,_) =>
      log.warning("Server identified with wrong correlationId. Restarting...")
      self ! PoisonPill

    case RequestData(offset,length) =>
      log.debug(s"Received a write request for offset: $offset and length: $length")

      (1 to length).foldLeft(offset) { (accum, next) =>
        sender ! WriterData(accum)
        accum + 1
      }
    case Terminated(s) =>
      log.warning(s"server $s terminated. Restarting...")
      self ! PoisonPill
  }

}

object WriterActor {
  def props(server: ActorSelection) = Props(classOf[WriterActor], server)
  case class RequestData(offset: Int, length: Int)
}

class ReaderActor(serverActor: ActorSelection, nrSequences: Int) extends Actor with ActorLogging {
  import ReaderActor._

  val idMap = new HashMap[UUID, Int]
  val correlationId = UUID.randomUUID()

  override def preStart() = {
    log.debug("Indentifying server")
    serverActor ! Identify(correlationId)
  }

  def receive: Receive = {
    case ActorIdentity(`correlationId`, Some(server)) =>
      log.debug(s"Identified $server Starting - creating $nrSequences random UUIDs")
      context.watch(server)
      (1 to nrSequences) foreach { _ =>
        val id = UUID.randomUUID()
        idMap.put(id, 0)
        server ! ServerActor.ReaderRequest(id, 0)
      }
    case ActorIdentity(`correlationId`, None) =>
      log.warning("No server identified. Restarting...")
      self ! PoisonPill
    case ActorIdentity(_,_) =>
      log.warning("Server identified with wrong correlationId. Restarting...")
      self ! PoisonPill

    case SequenceUpdate(id, count) if count > -1 =>
      log.debug(s"Received an update for id: $id and count: $count")
      idMap.put(id, count)

    case SequenceUpdate(id, count) if count == -1 =>
      log.debug(s"Received a deletion for id: $id")
      idMap.remove(id)
      sender ! ServerActor.RemoveId(id)
      
    case ReadMore =>
      val remaining = (nrSequences - idMap.size)
      log.debug(s"creating $remaining random UUIDs")
      (1 to remaining) foreach {_ =>
        val newId = UUID.randomUUID()
        idMap.put(newId, 0)
        sender ! ServerActor.ReaderRequest(newId, 0)
      }
      
    case Terminated(s) =>
      log.warning(s"server $s terminated. Restarting...")
      self ! PoisonPill
  }
}

object ReaderActor {
  def props(server: ActorSelection, nrSequences: Int = 1000) = Props(classOf[ReaderActor], server, nrSequences)
  case class SequenceUpdate(uuid: UUID, count: Int)
  case object ReadMore
}