package com.example

import akka.actor._
import akka.persistence._
import java.util.UUID

import scala.collection.mutable.Queue
import scala.collection.mutable.HashMap
import scala.concurrent.duration._

class ServerActor extends PersistentActor with ActorLogging {
  import ServerActor._
  import context.dispatcher

  override def persistenceId = "sample-id-1"
  
  //the queue with sequences to process
  val idQueue = new Queue[UUID]
  //the current sequence being processed and the count of numbers already sent
  var currentState: Option[(UUID, Int)] = None
  
  //the queue with numbers received from Writer
  val nrsQueue = new Queue[Int]
  //the next number to ask to Writer
  var nextWriterNumber = 1
  
  var readerActor: Option[ActorRef] = None
  var writerActor: Option[ActorRef] = None
  
  //the count of how many numbers to be received from writer 
  var requestingNumbers = 0
  
  var cancelable: Cancellable = null
  override def preStart() = {
    log.debug("In ServerActor - preStart scheduling")
    cancelable = context.system.scheduler.schedule(0 seconds, 1 second){
      self ! RequestNumbers
    }
  }
  
  override def postStop() = {
    super.postStop()
    log.debug("In ServerActor - postStop cancel scheduling")
    cancelable.cancel()
  }
  
  def updateState(evt: Evt): Unit = {
    updateNrsQueue(evt)
  }
  
  def updateSequencesQueue(cmd: Cmd): Unit = {
    cmd match {
      case WriterData(int)  =>
      case ReaderRequest(id, _) =>
        currentState match { 
          case None => currentState = Some(id, 1)
          case Some(_) => idQueue.enqueue(id)
        }
      case Acknowledge(id) =>
        currentState match {
          case Some((id, count)) => currentState = Some(id, count+1)
          case None => log.warning("")
        }
      case RemoveId(id) =>
        if(idQueue.isEmpty) {
          currentState = None
        }
        else {
          currentState = Some(idQueue.dequeue(), 1)
        }
    }
  }
  
  def updateNrsQueue(evt: Evt) = {
    evt match {
      case WriterEvt(int)  =>
        nrsQueue.enqueue(int)
        nextWriterNumber=int+1
      case AckEvt(id) =>
        nrsQueue.dequeue()
    }
  }
  
  def updateReader(reader: ActorRef) = {
    context.watch(reader)
    readerActor = Some(reader)
  }

  def printState() = {
    log.info(s"Current state: {nrsQueue: ${nrsQueue.size}; nextNumber: $nextWriterNumber; idQueue: ${idQueue.size}; currentState: $currentState}")
  }

  val receiveRecover: Receive = {
    case RecoveryCompleted => 
      log.info("Recovery complete")
      printState()
    case evt: Evt => updateState(evt);
    //    case SnapshotOffer(_, snapshot) => () //state = snapshot
  }

  val receiveCommand: Receive = {
    //messages from writer
    case WriterGreet =>
      log.debug("In ServerActor - greet")
      writerActor = Some(sender())
      requestingNumbers = 0

    case WriterData(int) =>
      log.debug(s"In ServerActor - WriterData $int")
      persist(WriterEvt(int)){ event =>
        updateState(event)
        requestingNumbers -= 1
      }

    //messages from reader 
    case r@ReaderRequest(uuid, count) =>
      log.debug("In ServerActor - Reader Request")
      updateReader(sender)
      val previousState = currentState
      updateSequencesQueue(r)
      (currentState, previousState) match {
        case (Some((currentId, currentCount)), None) => sendToReader(currentId, currentCount)
        case (Some((currentId, currentCount)), Some((previousId, previousCount))) => log.debug(s"Already handling a readrequest for ($currentId, $currentCount). Doing nothing")
        case (None, _) => log.warning("In ServerActor - State wrongfully not updated")
      }

    case a@Acknowledge(uuid) =>
      log.debug(s"In ServerActor - Reader acknowledged an update for id $uuid")
      updateReader(sender)
      currentState match {
        case Some((id, _)) if uuid == id =>
          persist(AckEvt(id)) { event =>
            updateState(event)
            updateSequencesQueue(a)
            currentState.fold { log.warning("In ServerActor - no more messages to process") } { case (id, count) =>
              sendToReader(id, count)
            }
          }
        case Some((id, count)) if uuid != id =>
          log.warning("In ServerActor - Received acknowledgement from reader but for a different UUID")

        case None =>
          log.warning("In ServerActor - Received acknowledgement but no current state is assigned")
      }

    case r@RemoveId(id) =>
      log.debug("In ServerActor - Remove ID")
      updateReader(sender)
      updateSequencesQueue(r)
      currentState.fold { log.warning("In ServerActor - removed a sequence and no more messages to process") } { case (newId, count) =>
        sendToReader(newId, count)
      }

      
    //Internal
    case RequestNumbers =>
      log.debug("In ServerActor - RequestNumbers")
      writerActor match {
        case Some(writer) =>
          if(requestingNumbers != 0)
            log.debug("In ServerActor - Already requested numbers")
          else if(nrsQueue.size >= 20) {
            log.debug(s"In ServerActor - No numbers necessary. Got ${nrsQueue.size} numbers")
          } else {
            log.debug("In ServerActor - Requesting Numbers")
            requestingNumbers = ReaderRequestDataLength
            writer ! WriterActor.RequestData(nextWriterNumber, ReaderRequestDataLength)
          }
        case None =>
          log.debug("In ServerActor - Received a RequestNumbers message but no writer actor is assigned. ")
      }
      
    case Retry =>
      log.debug("In ServerActor - Retry")
      currentState.fold { log.warning("In ServerActor - no more messages to process") } { case (id, count) =>
        sendToReader(id, count)
      }
      
    case Terminated(s) =>
      log.warning(s"reader $s terminated. discarting state")
      idQueue.clear()
      currentState = None
      
    case "print" => printState()
  }
  
  def sendToReader(id: UUID, count: Int) = {
    nrsQueue.headOption match {
      case None => 
        self ! RequestNumbers
        self ! Retry
      case Some(nr) =>
        readerActor match {
          case None => log.warning("In ServerActor - No reader actor is assigned")
          case Some(reader) =>
            //count can be 11 after an Ack so we test "count < 11"
            if(count < 11) {
              log.debug(s"Sending to reader sequence with id $id number $nr with count $count")
            	reader ! ReaderActor.SequenceUpdate(id, nr)
            }
            else if (count == 11){
              log.debug(s"Sending to reader completion of sequence with id $id (count was $count)")
              reader ! ReaderActor.SequenceUpdate(id, -1)
            } else {
              log.warning(s"Already sent termination message. Not sending more...")
            }
        }
    }
  }
}

object ServerActor {
  val props = Props[ServerActor]
  sealed trait Cmd
  case object WriterGreet
  case class WriterData(i: Int) extends Cmd
  
  case class ReaderRequest(uuid: UUID, i: Int) extends Cmd
  case class RemoveId(uuid: UUID) extends Cmd
  case class Acknowledge(uuid: UUID) extends Cmd
  
  case object RequestNumbers
  case object Retry

  sealed trait Evt
  case class WriterEvt(count: Int) extends Evt
  case class AckEvt(uuid: UUID) extends Evt
  
  val ReaderRequestDataLength = 15
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
          sender ! ServerActor.Acknowledge(id)
        case Some(c) if c >= count =>
          log.warning(s"In ReaderActor - server sent an unordered count for id: $id current: $c countReceived: $count")
//          idMap.put(id, 0)
//          serverActor ! ServerActor.RemoveId(id)
//          serverActor ! ServerActor.ReaderRequest(id, 0)
        case None =>
          log.warning(s"In ReaderActor - map does not contain $id. Acknowledging...")
          sender ! ServerActor.Acknowledge(id)
          
      }

    case SequenceUpdate(id, count) if count == -1 =>
      log.debug(s"In ReaderActor - Received a deletion for id: $id")
      val removed = idMap.remove(id)
      completedSequences += 1
      sender ! ServerActor.RemoveId(id)

      if(removed.isDefined) {
        log.debug("In ReaderActor - Requesting another sequence...")
        val newId = UUID.randomUUID()
        idMap.put(newId, 0)
        sender ! ServerActor.ReaderRequest(newId, 0)
      } else {
        log.debug(s"In ReaderActor - Map didn't contain id $id. Not requesting another sequence")
      }

    case Terminated(s) =>
      log.warning(s"In ReaderActor - server $s terminated. Restarting...")
      self ! PoisonPill

    case "print" =>
      log.info(s"In ReaderActor - Current state contains $completedSequences completed sequences and ${idMap.size} pending sequences")
  }
}

object ReaderActor {
  def props(server: ActorSelection, nrSequences: Int) = Props(classOf[ReaderActor], server, nrSequences)
  case class SequenceUpdate(uuid: UUID, count: Int)
}