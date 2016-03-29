package com.example

import akka.actor._
import java.util.UUID

import scala.collection.mutable.HashMap

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
      //start by sending nrSequences to the server to start counting
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
        //Received an ordered count, acknowledge it
        case Some(c) if c < count =>
          idMap.put(id, count)
          sender ! ServerActor.Acknowledge(id)
        //Received an unordered count, restarting it
        case Some(c) if c >= count =>
          log.warning(s"In ReaderActor - server sent an unordered count for id: $id current: $c countReceived: $count")
          idMap.put(id, 0)
          serverActor ! ServerActor.RemoveId(id)
          serverActor ! ServerActor.ReaderRequest(id, 0)
        case None =>
          log.warning(s"In ReaderActor - map does not contain $id. Acknowledging...")
          sender ! ServerActor.Acknowledge(id)
      }

    //Delete the current sequence and request a new one
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