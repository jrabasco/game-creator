package database

import akka.actor.{ActorContext, ActorLogging, Actor}

/**
 * Created by roger on 17/11/14.
 */
trait DatabaseService extends Actor with ActorLogging{
  def actorRefFactory: ActorContext = context

}