package com.resy

import org.apache.logging.log4j.scala.Logging
import org.joda.time.DateTime
import play.api.libs.json.{JsArray, JsValue, Json}

import scala.concurrent.{ExecutionContext, Future, blocking}
import scala.concurrent.duration._
import scala.language.postfixOps

class ResyClient(resyApi: ResyApi)(implicit ec: ExecutionContext) extends Logging {

  private type ReservationMap = Map[String, TableTypeMap]
  private type TableTypeMap   = Map[String, String]

  import ResyClientErrorMessages._

  /** Delay helper that returns a Future that completes after the specified delay */
  private def delay(millis: Long): Future[Unit] = {
    Future {
      blocking {
        Thread.sleep(millis)
      }
    }
  }

  /** Calculate backoff delay based on attempt number and error type */
  private def calculateBackoff(attemptNumber: Int, isRateLimited: Boolean): Long = {
    if (isRateLimited) {
      // For 429 errors: longer backoff (500ms, 1000ms, 2000ms, capped at 3000ms)
      math.min(500L * math.pow(2, attemptNumber - 1).toLong, 3000L)
    } else {
      // For other retries: shorter backoff (200ms, 400ms, 800ms, capped at 1500ms)
      math.min(200L * math.pow(2, attemptNumber - 1).toLong, 1500L)
    }
  }

  /** Tries to find a reservation based on the priority list of requested reservations times. Due to
    * race condition of when the bot runs and when the times become available, retry may be
    * required.
    * @param date
    *   Date of the reservation in YYYY-MM-DD format
    * @param partySize
    *   Size of the party reservation
    * @param venueId
    *   Unique identifier of the restaurant where you want to make the reservation
    * @param resTimeTypes
    *   Priority list of reservation times and table types. Time is in military time HH:MM:SS
    *   format.
    * @param millisToRetry
    *   Optional parameter for how long to try to find a reservations in milliseconds
    * @return
    *   Future containing configId which is the unique identifier for the reservation
    */
  def findReservations(
    date: String,
    partySize: Int,
    venueId: Int,
    resTimeTypes: Seq[ReservationTimeType],
    millisToRetry: Long = (10 seconds).toMillis
  ): Future[String] = {
    val deadline = DateTime.now.getMillis + millisToRetry

    def attempt(attemptNumber: Int = 1): Future[String] = {
      resyApi.getReservations(date, partySize, venueId).flatMap { response =>
        logger.debug(s"URL Response: $response")

        parseAndFindReservation(response, resTimeTypes) match {
          case Right(configId) =>
            logger.info(s"Config Id: $configId")
            Future.successful(configId)
          case Left(error) if DateTime.now.getMillis < deadline =>
            val backoff = calculateBackoff(attemptNumber, isRateLimited = false)
            logger.debug(s"Retrying find in ${backoff}ms: $error")
            delay(backoff).flatMap(_ => attempt(attemptNumber + 1))
          case Left(error) =>
            logger.info("Missed the shot!")
            logger.info("""┻━┻ ︵ \(°□°)/ ︵ ┻━┻""")
            logger.info(error)
            Future.failed(new RuntimeException(error))
        }
      }.recoverWith {
        case e if e.getMessage.contains("429") && DateTime.now.getMillis < deadline =>
          // Rate limited - use longer backoff
          val backoff = calculateBackoff(attemptNumber, isRateLimited = true)
          logger.warn(s"Rate limited (429), backing off for ${backoff}ms...")
          delay(backoff).flatMap(_ => attempt(attemptNumber + 1))
        case e if DateTime.now.getMillis < deadline =>
          val backoff = calculateBackoff(attemptNumber, isRateLimited = false)
          logger.debug(s"Retrying after error in ${backoff}ms: ${e.getMessage}")
          delay(backoff).flatMap(_ => attempt(attemptNumber + 1))
        case e =>
          logger.info("Missed the shot!")
          logger.info("""┻━┻ ︵ \(°□°)/ ︵ ┻━┻""")
          logger.info(s"$noAvailableResMsg: ${e.getMessage}")
          Future.failed(new RuntimeException(noAvailableResMsg))
      }
    }

    attempt()
  }

  /** Parse response and find matching reservation based on priority list
    */
  private def parseAndFindReservation(
    response: String,
    resTimeTypes: Seq[ReservationTimeType]
  ): Either[String, String] = {
    try {
      val json = Json.parse(response)

      // Validate response structure
      val slotsOpt = (json \ "results" \ "venues" \ 0 \ "slots").asOpt[JsArray]

      slotsOpt match {
        case None =>
          Left(s"Invalid response structure: missing slots. Response: ${response.take(200)}")
        case Some(slots) if slots.value.isEmpty =>
          Left("No slots available in response")
        case Some(slots) =>
          val reservationMap = buildReservationMap(slots.value.toSeq)
          if (reservationMap.isEmpty) {
            Left("Empty reservation map")
          } else {
            findReservationTime(reservationMap, resTimeTypes)
          }
      }
    } catch {
      case e: Exception =>
        Left(s"Failed to parse response: ${e.getMessage}. Response: ${response.take(200)}")
    }
  }

  /** Get details of the reservation
    * @param configId
    *   Unique identifier for the reservation
    * @param date
    *   Date of the reservation in YYYY-MM-DD format
    * @param partySize
    *   Size of the party reservation
    * @return
    *   Future containing the paymentMethodId and the bookingToken of the reservation
    */
  def getReservationDetails(configId: String, date: String, partySize: Int): Future[BookingDetails] = {
    resyApi.getReservationDetails(configId, date, partySize).map { response =>
      logger.debug(s"URL Response: $response")

      val resDetails = Json.parse(response)

      // Validate and extract payment method ID
      val paymentMethodId = (resDetails \ "user" \ "payment_methods" \ 0 \ "id")
        .asOpt[Int]
        .getOrElse {
          throw new RuntimeException(
            s"Missing payment_methods in response. Got: ${response.take(300)}"
          )
        }

      logger.info(s"Payment Method Id: $paymentMethodId")

      // Validate and extract book token
      val bookToken = (resDetails \ "book_token" \ "value")
        .asOpt[String]
        .getOrElse {
          throw new RuntimeException(
            s"Missing book_token in response. Got: ${response.take(300)}"
          )
        }

      logger.info(s"Book Token: $bookToken")

      BookingDetails(paymentMethodId, bookToken)
    }.recoverWith { case e =>
      logger.info("Missed the shot!")
      logger.info("""┻━┻ ︵ \(°□°)/ ︵ ┻━┻""")
      logger.info(s"$unknownErrorMsg: ${e.getMessage}")
      Future.failed(new RuntimeException(unknownErrorMsg))
    }
  }

  /** Book the reservation
    * @param paymentMethodId
    *   Unique identifier of the payment id in case of a late cancellation fee
    * @param bookToken
    *   Unique identifier of the reservation in question
    * @param dryRun
    *   If true, skip actual booking and return a fake token
    * @return
    *   Future containing unique identifier of the confirmed booking
    */
  def bookReservation(paymentMethodId: Int, bookToken: String, dryRun: Boolean = false): Future[String] = {
    if (dryRun) {
      logger.info("[DRY-RUN] Would book reservation")
      logger.info(s"[DRY-RUN] Payment Method Id: $paymentMethodId")
      logger.info(s"[DRY-RUN] Book Token: $bookToken")
      logger.info("Headshot! (dry-run)")
      logger.info("(҂‾ ▵‾)︻デ═一 (× _ ×#")
      Future.successful("DRY-RUN-TOKEN")
    } else {
      resyApi.postReservation(paymentMethodId, bookToken).map { response =>
        logger.debug(s"URL Response: $response")

        val resyToken = (Json.parse(response) \ "resy_token")
          .asOpt[String]
          .getOrElse {
            throw new RuntimeException(
              s"Missing resy_token in response. Got: ${response.take(300)}"
            )
          }

        logger.info("Headshot!")
        logger.info("(҂‾ ▵‾)︻デ═一 (× _ ×#")
        logger.info("Successfully sniped reservation")
        logger.info(s"Resy token is $resyToken")
        resyToken
      }.recoverWith { case e =>
        logger.info("Missed the shot!")
        logger.info("""┻━┻ ︵ \(°□°)/ ︵ ┻━┻""")
        logger.info(s"$resNoLongerAvailMsg: ${e.getMessage}")
        Future.failed(new RuntimeException(resNoLongerAvailMsg))
      }
    }
  }

  private def findReservationTime(
    reservationMap: ReservationMap,
    resTimeTypes: Seq[ReservationTimeType]
  ): Either[String, String] = {
    resTimeTypes.view
      .map { resTimeType =>
        reservationMap.get(resTimeType.reservationTime).flatMap { tableTypes =>
          resTimeType.tableType match {
            case Some(tableType) if tableType.nonEmpty => tableTypes.get(tableType.toLowerCase)
            case _                                     => Some(tableTypes.head._2)
          }
        }
      }
      .collectFirst { case Some(configId) => configId }
      .toRight(cantFindResMsg)
  }

  private def buildReservationMap(reservationTimes: Seq[JsValue]): ReservationMap = {
    // Build map from these JSON objects...
    // {"config": {"type":"TABLE_TYPE", "token": "CONFIG_ID"},
    // "date": {"start": "2099-01-30 17:00:00"}}
    reservationTimes.foldLeft(Map.empty[String, TableTypeMap]) { case (reservationMap, reservation) =>
      try {
        val time = (reservation \ "date" \ "start")
          .asOpt[String]
          .map(_.dropWhile(_ != ' ').drop(1))
          .getOrElse("")

        val config    = reservation \ "config"
        val tableType = (config \ "type").asOpt[String].map(_.toLowerCase).getOrElse("")
        val configId  = (config \ "token").asOpt[String].getOrElse("")

        if (time.isEmpty || configId.isEmpty) {
          reservationMap
        } else if (!reservationMap.contains(time)) {
          reservationMap.updated(time, Map(tableType -> configId))
        } else {
          reservationMap.updated(time, reservationMap(time).updated(tableType, configId))
        }
      } catch {
        case _: Exception => reservationMap
      }
    }
  }
}

object ResyClientErrorMessages {
  val noAvailableResMsg   = "Could not find any available reservations"
  val cantFindResMsg      = "Could not find a reservation for the given time(s)"
  val unknownErrorMsg     = "Unknown error occurred"
  val resNoLongerAvailMsg = "Reservation no longer available"
}

final case class BookingDetails(paymentMethodId: Int, bookingToken: String)
