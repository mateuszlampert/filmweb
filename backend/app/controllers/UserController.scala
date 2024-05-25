package controllers

import javax.inject._
import play.api._
import play.api.mvc._
import slick.jdbc.PostgresProfile.api._
import scala.concurrent.ExecutionContext.Implicits.global
import models.User
import scala.concurrent.Future
import play.api.libs.json.Json
import play.api.mvc.Results._
import services.UserService


@Singleton
class UserController @Inject()(val controllerComponents: ControllerComponents, userService: UserService) extends BaseController {
  val db = Database.forConfig("postgres")

  def login() = Action.async { implicit request: Request[AnyContent] =>
    userService.login(request)
  }

  def register() = Action.async { implicit request: Request[AnyContent] =>
    userService.register(request)
  }



  def rate() = Action.async { implicit request: Request[AnyContent] =>
    val requestBodyJson = request.body.asJson
    requestBodyJson.map { json =>
      val movieId = (json \ "movieId").as[Int]
      val userId = (json \ "userId").as[Int]
      val stars = (json \ "stars").as[Int]
      val review = (json \ "review").as[String]

      hasAlreadyGivenRating(userId, movieId).flatMap { hasRated =>
        if (!hasRated) {
          postNewRating(movieId, userId, stars, review)
        } else {
          Future.successful(BadRequest(Json.obj("message" -> "This user already rated this film.")))
        }
      }
    }.getOrElse {
      Future.successful(BadRequest("Expecting application/json request body"))
    }
  }

  def hasAlreadyGivenRating(userId: Int, movieId: Int): Future[Boolean] = {
    val query = sql"""
           SELECT stars
           FROM ratings
           WHERE user_id = $userId AND movie_id = $movieId
           """.as[Double]
    db.run(query.headOption).map {
      case Some(rating) =>
        true
      case _ =>
        false
    }
  }

  def postNewRating(movieId: Int, userId: Int, stars: Double, review: String) = {
    val query = sql"""
           SELECT average_rating, num_ratings
           FROM movies
           WHERE movie_id = $movieId
           """.as[(Double, Int)]

    db.run(query.headOption).flatMap {
      case Some(average_rating, num_ratings) =>
        val newNumRatings = num_ratings + 1
        val newRating = ((num_ratings * average_rating) + stars) / newNumRatings

        val updateQuery =
          sqlu"""
                UPDATE movies
                SET average_rating = $newRating , num_ratings = $newNumRatings
                WHERE movie_id = $movieId
                """
        val insertReviewQuery =
          sqlu"""
                INSERT INTO ratings (movie_id, user_id, stars, review)
                VALUES ($movieId, $userId, $stars, $review)
                """

        for {
          _ <- db.run(updateQuery)
          _ <- db.run(insertReviewQuery)
        } yield Ok(Json.obj("message" -> "Added new rating and review"))

      case None =>
        Future.successful(NotFound(Json.obj("message" -> "There isn't such film!")))
    }
  }
}