package com.example

import akka.actor._
import java.util.UUID

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

      //iterate from the received offset until length, sending the next numbers
      //e.g Received (5,3) - Send 5 , 6 , 7
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