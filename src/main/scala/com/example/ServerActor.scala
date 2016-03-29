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
  //Ask for more numbers, if needed, every second
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
        //if we are already processing a sequence, enqueue the incoming id, otherwise start processing it
        currentState match { 
          case None => currentState = Some(id, 1)
          case Some(_) => idQueue.enqueue(id)
        }
      case Acknowledge(id) =>
        currentState match {
            //This is only called when currentId == id
          case Some((currentId, count)) => currentState = Some(id, count+1)
          case None => log.warning("In ServerActor - Received an acknowledge for an uuid but no current id assigned")
        }
      case RemoveId(id) =>
        //if the idQueue is empty, we have no more ids to process, otherwise start processing the next one
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
    case evt: Evt => updateState(evt)
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

    //Receive an acknowledgement from the reader and possibly start processing the next integer for the sequence
    case a@Acknowledge(uuid) =>
      log.debug(s"In ServerActor - Reader acknowledged an update for id $uuid")
      updateReader(sender)
      currentState match {
        case Some((id, _)) if uuid == id =>
          persist(AckEvt(id)) { event =>
            updateState(event)
            updateSequencesQueue(a)
            currentState.fold { log.warning("In ServerActor - no more messages to process") } { case (currentId, currentCount) =>
              sendToReader(currentId, currentCount)
            }
          }
        case Some((id, count)) if uuid != id =>
          log.warning("In ServerActor - Received acknowledgement from reader but for a different UUID")

        case None =>
          log.warning("In ServerActor - Received acknowledgement but no current state is assigned")
      }

    //Start processing the next sequence
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

  //This function sends messages to the reader - If the count is < 11 we're still iterating the current sequence,
  //if it is equal to 11 we send (id, -1) to tell the reader we're ready to start processing the next sequence.
  def sendToReader(id: UUID, count: Int) = {
    nrsQueue.headOption match {
      case None => 
        self ! RequestNumbers
        //do not crush server with retries
        context.system.scheduler.scheduleOnce(100 milliseconds){
          self ! Retry
        }
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
