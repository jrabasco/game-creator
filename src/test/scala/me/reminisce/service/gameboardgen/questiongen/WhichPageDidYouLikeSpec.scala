package me.reminisce.service.gameboardgen.questiongen

import java.util.concurrent.TimeUnit

import akka.testkit.{TestActorRef, TestProbe}
import me.reminisce.database.MongoDatabaseService
import me.reminisce.mongodb.MongoDBEntities.{FBPage, FBPageLike}
import me.reminisce.service.gameboardgen.GameboardEntities.MultipleChoiceQuestion
import me.reminisce.service.gameboardgen.questiongen.QuestionGenerator.{CreateQuestion, NotEnoughData}
import org.joda.time.DateTime
import org.scalatest.DoNotDiscover
import reactivemongo.api.collections.default.BSONCollection

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration

@DoNotDiscover
class WhichPageDidYouLikeSpec extends QuestionTester("WhichPageDidYouLikeSpec") {

  val userId = "TestUserWhichPageDidYouLike"

  "WhichPageDidYouLike" must {
    "not create question when there is not enough data." in {
      val db = newDb()
      val itemId = "This user does not exist"

      val actorRef = TestActorRef(WhichPageDidYouLike.props(db))
      val testProbe = TestProbe()
      testProbe.send(actorRef, CreateQuestion(userId, itemId))
      testProbe.expectMsg(NotEnoughData(s"Page not found. $itemId"))
    }

    "create a valid question when the data is there." in {
      val db = newDb()
      val pagesCollection = db[BSONCollection](MongoDatabaseService.fbPagesCollection)

      val pagesNumber = 4 // MC question

      val itemIds: List[String] = (1 to pagesNumber).map {
        case nb => s"Page$nb"
      }.toList

      val pages = (0 until pagesNumber).map {
        case nb =>
          FBPage(None, itemIds(nb), Some(s"Cool page with id $nb"), None, nb)
      }.toList

      (0 until pagesNumber) foreach {
        case nb =>
          Await.result(pagesCollection.save(pages(nb), safeLastError), Duration(10, TimeUnit.SECONDS))
      }

      val pageLikesCollection = db[BSONCollection](MongoDatabaseService.fbPageLikesCollection)
      val pageLike = FBPageLike(None, userId, itemIds.head, DateTime.now)
      Await.result(pageLikesCollection.save(pageLike, safeLastError), Duration(10, TimeUnit.SECONDS))

      val actorRef = TestActorRef(WhichPageDidYouLike.props(db))
      val testProbe = TestProbe()
      testProbe.send(actorRef, CreateQuestion(userId, itemIds.head))

      checkFinished[MultipleChoiceQuestion](testProbe) {
        question =>
          val possibilitiesIds = question.choices.map {
            poss =>
              poss.fbId match {
                case Some(id) => id
                case None => fail(s"ID is not defined for possibility $poss.")
              }
          }
          val answer = question.answer

          pages.headOption match {
            case Some(pge) =>
              assert(possibilitiesIds(answer) == pge.pageId)
            case None =>
              fail("Pages is empty.")
          }
      }
    }
  }

}