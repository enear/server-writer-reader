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

  def updateState(evt: Evt): Unit = {
    evt match {
      case WriterEvt(id, count)  =>
        currentState = Some(id, count)
      case ReaderEvt(id) =>
        currentState.fold { currentState = Some(id, 1) }  { _ => idQueue.enqueue(id) }
      case RemoveIdEvt(id) =>
        idQueue.size match {
          case 0 => log.debug("In ServerActor - Tried to dequeue but no UUIDs left to process")
            currentState = None
          case n => currentState = Some(idQueue.dequeue(), 1)
        }
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
          log.debug(s"In ServerActor - WriterData for id $id and count $count")
          readerActor
            .fold { log.error("In ServerActor - Received a write message but no reader actor is assigned") }
            { reader => reader ! ReaderActor.SequenceUpdate(id, count) }
        case None => log.error("In ServerActor - Received a write message but no currentId is assigned")
      }

    case ReaderRequest(uuid, count) =>
      log.debug("In ServerActor - Reader Request")

      if(readerActor.isEmpty) readerActor = Some(sender())
      val firstMessage = currentState.isEmpty
      persist(ReaderEvt(uuid)){ event =>
        updateState(event)

        currentState.fold { log.warning("In ServerActor - State wrongfully not updated") } { case (_, currentCount) =>
          if(firstMessage)
            self ! WriterRequest(currentCount)
          else
            log.debug("Not first message. Doing nothing")
        }
      }

    case Acknowledge(uuid) =>
      log.debug(s"In ServerActor - Reader acknowledged an update for id $uuid")
      currentState match {
        case Some((id, count)) if uuid == id =>
          persist(WriterEvt(id, count)) { event =>
            updateState(event)
            if (count == 10) {
              readerActor
                .fold {
                  log.error("In ServerActor - Received a write message but no reader actor is assigned")
                } { reader =>
                  reader ! ReaderActor.SequenceUpdate(id, -1)
                }
            } else {
              self ! WriterRequest(count + 1)
            }
          }
        case Some((id, count)) if uuid != id =>
          log.warning("In ServerActor - Received acknowledgement from reader but for a different UUID")

        case None =>
          log.warning("In ServerActor - Received acknowledgement but no current state is assigned")
       }

    case RemoveId(id) =>
      log.debug("In ServerActor - Remove ID")
      persist(RemoveIdEvt(id)) { event =>
        updateState(event)

        currentState.fold { log.warning("In ServerActor - removed a sequence and no more messages to process") } { case (newId, count) =>
          self ! WriterRequest(count)
        }
      }

    case WriterRequest(count) =>
      log.debug("In ServerActor - Writer Request")
      writerActor match {
        case Some(writer) =>
          writer ! RequestData(count, 1)
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
  case class Acknowledge(uuid: UUID)

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
  var completedSequences = 0l
  val correlationId = UUID.randomUUID()

  override def preStart() = {
    log.debug("In ReaderActor - Indentifying server")
    serverActor ! Identify(correlationId)
  }

  def receive: Receive = {
    case ActorIdentity(`correlationId`, Some(server)) =>
      log.debug(s"In ReaderActor - Identified $server Starting - creating $nrSequences random UUIDs")
      context.watch(server)
      (1 to nrSequences) foreach { _ =>
        val id = UUID.randomUUID()
        idMap.put(id, 0)
        server ! ServerActor.ReaderRequest(id, 0)
      }
    case ActorIdentity(`correlationId`, None) =>
      log.warning("In ReaderActor - No server identified. Restarting...")
      self ! PoisonPill
    case ActorIdentity(_,_) =>
      log.warning("In ReaderActor - Server identified with wrong correlationId. Restarting...")
      self ! PoisonPill

    case SequenceUpdate(id, count) if count > -1 =>
      log.debug(s"In ReaderActor - Received an update for id: $id and count: $count")
      idMap.get(id) match {
        case Some(c) if c < count =>
          idMap.put(id, count)
          serverActor ! ServerActor.Acknowledge(id)
        case Some(c) if c >= count =>
          log.warning(s"In ReaderActor - server sent an unordered count for id: $id current: $c countReceived: $count")
//          idMap.put(id, 0)
//          serverActor ! ServerActor.RemoveId(id)
//          serverActor ! ServerActor.ReaderRequest(id, 0)
        case None =>
          log.warning(s"In ReaderActor - map does not contain $id")
      }

    case SequenceUpdate(id, count) if count == -1 =>
      log.debug(s"In ReaderActor - Received a deletion for id: $id")
      idMap.remove(id)
      completedSequences += 1
      sender ! ServerActor.RemoveId(id)

      log.debug("In ReaderActor - Requesting another sequence...")
      val newId = UUID.randomUUID()
      idMap.put(newId, 0)
      sender ! ServerActor.ReaderRequest(newId, 0)

    case Terminated(s) =>
      log.warning(s"In ReaderActor - server $s terminated. Restarting...")
      self ! PoisonPill

    case "print" =>
      log.info(s"In ReaderActor - Current state contains $completedSequences completed sequences and ${idMap.size} pending sequences")
  }
}

object ReaderActor {
  def props(server: ActorSelection, nrSequences: Int = 1000) = Props(classOf[ReaderActor], server, nrSequences)
  case class SequenceUpdate(uuid: UUID, count: Int)
}