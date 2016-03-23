package com.example

import akka.actor.{Actor, ActorLogging, Props}

class ServerActor extends Actor with ActorLogging {
  import ServerActor._
  
  def receive = {
  	case Greet => 
	    log.info("In ServerActor - greet")
  }	
}

object ServerActor {
  val props = Props[ServerActor]
  case object Greet
}