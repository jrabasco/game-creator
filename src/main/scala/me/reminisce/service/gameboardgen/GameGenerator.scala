package me.reminisce.service.gameboardgen

import akka.actor._
import me.reminisce.fetcher.FetcherService
import me.reminisce.fetcher.FetcherService.FetchData
import me.reminisce.server.domain.Domain._
import me.reminisce.server.domain.{Domain, RestMessage}
import me.reminisce.service.gameboardgen.BoardGenerator.{FailedBoardGeneration, FinishedBoardGeneration}
import me.reminisce.service.gameboardgen.GameGenerator.{CreateBoard, InitBoardCreation}
import me.reminisce.service.gameboardgen.GameboardEntities.{Board, Tile}
import reactivemongo.api.DefaultDB

import scala.concurrent.ExecutionContextExecutor

object GameGenerator {

  def props(database: DefaultDB, userId: String): Props =
    Props(new GameGenerator(database, userId))

  case class CreateBoard(accessToken: String, strategy: String) extends RestMessage

  case class InitBoardCreation()

}

class GameGenerator(database: DefaultDB, userId: String) extends Actor with ActorLogging {
  implicit def dispatcher: ExecutionContextExecutor = context.dispatcher

  implicit def actorRefFactory: ActorContext = context

  def receive = {
    case CreateBoard(accessToken, strategy) =>
      val client = sender()
      val creator = getCreatorFromStrategy(strategy)
      val fetcherService = context.actorOf(FetcherService.props(database))
      creator ! InitBoardCreation()
      fetcherService ! FetchData(userId, accessToken)
      context.become(awaitFeedBack(client, creator, List()))
    case x => log.error("GameGenerator received unexpected Message " + x)
  }


  def getCreatorFromStrategy(strategy: String): ActorRef = strategy match {
    case "uniform" =>
      context.actorOf(Props(new UniformBoardGenerator(database, userId)))
    case "choose" =>
      context.actorOf(Props(new StrategyChooser(database, userId)))
    case "random" =>
      context.actorOf(Props(new FullRandomBoardGenerator(database, userId)))
    case any =>
      context.actorOf(Props(new StrategyChooser(database, userId)))
  }


  // Awaits feedback from the FetcherService and the tile creators
  def awaitFeedBack(client: ActorRef, worker: ActorRef, tiles: List[Tile],
                    fetcherAcked: Boolean = false, isTokenStale: Boolean = false): Receive = {
    case FinishedBoardGeneration(receivedTiles) =>
      context.become(awaitFeedBack(client, worker, receivedTiles, fetcherAcked, isTokenStale))
      verifyAndAnswer(client, receivedTiles, fetcherAcked, isTokenStale)
    case FailedBoardGeneration(message) =>
      worker ! PoisonPill
      client ! InternalError(message)
      log.error(s"An internal error occurred while generating the gameboard for user $userId.")
    case Done(message) =>
      verifyAndAnswer(client, tiles, ack = true, isTokenStale)
      context.become(awaitFeedBack(client, worker, tiles, fetcherAcked = true, isTokenStale))
      log.info(s"Update done. $message")
    case Domain.TooManyRequests(message) =>
      verifyAndAnswer(client, tiles, ack = true, isTokenStale)
      context.become(awaitFeedBack(client, worker, tiles, fetcherAcked = true, isTokenStale))
      log.info(message)
    case GraphAPIInvalidToken(message) =>
      verifyAndAnswer(client, tiles, ack = true, stale = true)
      context.become(awaitFeedBack(client, worker, tiles, fetcherAcked = true, isTokenStale = true))
      log.info(message)
    case GraphAPIUnreachable(message) =>
      verifyAndAnswer(client, tiles, ack = true, isTokenStale)
      context.become(awaitFeedBack(client, worker, tiles, fetcherAcked = true, isTokenStale))
      log.info(message)
    case AlreadyFresh(message) =>
      verifyAndAnswer(client, tiles, ack = true, isTokenStale)
      context.become(awaitFeedBack(client, worker, tiles, fetcherAcked = true, isTokenStale))
      log.info(message)
    case any =>
      log.error(s"GameGenerator received an unknown message : $any.")
  }

  def verifyAndAnswer(client: ActorRef, tiles: List[Tile], ack: Boolean, stale: Boolean): Unit = {
    if (tiles.length == 9 && ack) {
      client ! Board(userId, tiles, stale)
      sender() ! PoisonPill
    }
  }

}
