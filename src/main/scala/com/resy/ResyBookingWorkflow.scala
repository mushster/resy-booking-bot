package com.resy

import org.apache.logging.log4j.scala.Logging
import org.joda.time.DateTime

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.language.postfixOps

class ResyBookingWorkflow(
  resyClient: ResyClient,
  resDetails: ReservationDetails,
  dryRun: Boolean = false
)(implicit ec: ExecutionContext)
    extends Logging {

  private val restaurantName: String = resDetails.name.getOrElse(s"Venue ${resDetails.venueId}")

  /** Run the booking workflow asynchronously with timing metrics.
    * @param millisToRetry
    *   How long to retry the entire workflow on failure
    * @return
    *   Future containing the resy token on success
    */
  def run(millisToRetry: Long = (10 seconds).toMillis): Future[String] = {
    Metrics.timedWorkflow(restaurantName) {
      val deadline = DateTime.now.getMillis + millisToRetry

      def attempt(): Future[String] = {
        logger.info(s"[$restaurantName] Taking the shot...")
        logger.info(s"[$restaurantName] (҂‾ ▵‾)︻デ═一 (˚▽˚'!)/")
        logger.info(s"[$restaurantName] Attempting to snipe reservation")

        snipeReservation().recoverWith {
          case e if DateTime.now.getMillis < deadline =>
            logger.info(s"[$restaurantName] Retrying workflow: ${e.getMessage}")
            attempt()
        }
      }

      attempt()
    }
  }

  /** Execute the full find -> details -> book pipeline with timing.
    */
  private def snipeReservation(): Future[String] = {
    for {
      configId <- Metrics.timed("find", restaurantName) {
        resyClient.findReservations(
          date         = resDetails.date,
          partySize    = resDetails.partySize,
          venueId      = resDetails.venueId,
          resTimeTypes = resDetails.resTimeTypes
        )
      }
      bookingDetails <- Metrics.timed("details", restaurantName) {
        resyClient.getReservationDetails(
          configId  = configId,
          date      = resDetails.date,
          partySize = resDetails.partySize
        )
      }
      resyToken <- Metrics.timed("book", restaurantName) {
        resyClient.bookReservation(
          paymentMethodId = bookingDetails.paymentMethodId,
          bookToken       = bookingDetails.bookingToken,
          dryRun          = dryRun
        )
      }
    } yield resyToken
  }
}
