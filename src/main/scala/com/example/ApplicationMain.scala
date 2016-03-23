package com.example

import akka.actor.ActorSystem

object ApplicationMain extends App {
  val system = ActorSystem()
  val serverActor = system.actorOf(ServerActor.props, "serverActor")
  
  // This example app will ping pong 3 times and thereafter terminate the ActorSystem - 
  // see counter logic in PingActor
  system.awaitTermination()
}