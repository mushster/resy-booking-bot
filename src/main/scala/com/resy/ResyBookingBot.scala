package com.resy

import akka.actor.ActorSystem
import org.apache.logging.log4j.scala.Logging
import org.joda.time.DateTime
import pureconfig.ConfigSource
import pureconfig.generic.auto._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future, Promise}
import scala.language.postfixOps
import scala.util.{Failure, Success}

object ResyBookingBot extends Logging {

  def main(args: Array[String]): Unit = {
    logger.info("Starting Resy Booking Bot")

    val resyConfig = ConfigSource.resources("resyConfig.conf")
    val resyKeys   = resyConfig.at("resyKeys").loadOrThrow[ResyKeys]
    val snipeTime  = resyConfig.at("snipeTime").loadOrThrow[SnipeTime]
    val settings   = resyConfig.at("settings").load[Settings].getOrElse(Settings())

    // Load multiple restaurants
    val multiResDetails = resyConfig.at("resDetails").loadOrThrow[MultiReservationDetails]
    val restaurants     = multiResDetails.restaurants

    logger.info(s"Loaded ${restaurants.size} restaurant(s) to snipe")
    logger.info(s"Dry-run mode: ${settings.dryRun}")
    restaurants.foreach { r =>
      val name = r.name.getOrElse(s"Venue ${r.venueId}")
      logger.info(s"  - $name (venueId: ${r.venueId}, date: ${r.date}, party: ${r.partySize})")
    }

    val resyApi    = new ResyApi(resyKeys)
    val resyClient = new ResyClient(resyApi)

    val system      = ActorSystem("System")
    val dateTimeNow = DateTime.now
    val todaysSnipeTime = dateTimeNow
      .withHourOfDay(snipeTime.hours)
      .withMinuteOfHour(snipeTime.minutes)
      .withSecondOfMinute(0)
      .withMillisOfSecond(0)

    val nextSnipeTime =
      if (todaysSnipeTime.getMillis > dateTimeNow.getMillis) todaysSnipeTime
      else todaysSnipeTime.plusDays(1)

    // Fire exactly at snipe time (no early offset - that wastes rate limit budget)
    val millisUntilSnipe = nextSnipeTime.getMillis - DateTime.now.getMillis
    val hoursRemaining   = millisUntilSnipe / 1000 / 60 / 60
    val minutesRemaining = millisUntilSnipe / 1000 / 60 - hoursRemaining * 60
    val secondsRemaining =
      millisUntilSnipe / 1000 - hoursRemaining * 60 * 60 - minutesRemaining * 60

    logger.info(s"Next snipe time: $nextSnipeTime")
    logger.info(
      s"Sleeping for $hoursRemaining hours, $minutesRemaining minutes, and $secondsRemaining seconds"
    )

    val promise = Promise[Unit]()

    // Pre-warm connections 5 seconds before snipe time
    val preWarmMillis = millisUntilSnipe - 5000
    if (preWarmMillis > 0) {
      system.scheduler.scheduleOnce(preWarmMillis millis) {
        logger.info("Pre-warming connections...")
        val yesterday = DateTime.now.minusDays(1).toString("yyyy-MM-dd")
        restaurants.foreach { r =>
          val name = r.name.getOrElse(s"Venue ${r.venueId}")
          resyApi.getReservations(yesterday, r.partySize, r.venueId).onComplete {
            case Success(_) => logger.info(s"[$name] Connection warmed")
            case Failure(e) => logger.debug(s"[$name] Pre-warm failed (expected): ${e.getMessage}")
          }
        }
      }
    }

    system.scheduler.scheduleOnce(millisUntilSnipe millis) {
      logger.info(s"SNIPE TIME! Firing ${restaurants.size} booking attempts (staggered by 150ms)...")

      // Stagger restaurant attempts to avoid rate limiting
      // Each restaurant starts 150ms after the previous one
      val bookingFutures: Seq[Future[(ReservationDetails, String)]] =
        restaurants.zipWithIndex.map { case (resDetails, index) =>
          val name     = resDetails.name.getOrElse(s"Venue ${resDetails.venueId}")
          val workflow = new ResyBookingWorkflow(resyClient, resDetails, settings.dryRun)
          val staggerDelay = index * 150 // 0ms, 150ms, 300ms, etc.

          // Use akka scheduler for staggered start
          val startPromise = Promise[String]()
          system.scheduler.scheduleOnce(staggerDelay millis) {
            logger.info(s"[$name] Starting booking attempt...")
            workflow.run().onComplete {
              case Success(token) => startPromise.success(token)
              case Failure(ex)    => startPromise.failure(ex)
            }
          }

          startPromise.future.transform {
            case Success(token) =>
              logger.info(s"[$name] SUCCESS! Reservation confirmed with token: $token")
              Success((resDetails, token))
            case Failure(ex) =>
              logger.error(s"[$name] FAILED: ${ex.getMessage}")
              Failure(ex)
          }
        }

      // Wait for all bookings to complete (some may fail)
      val allResults = Future.sequence(
        bookingFutures.map(_.transform(Success(_))) // Convert failures to Success(Failure(...))
      )

      allResults.onComplete { results =>
        val outcomes = results.getOrElse(Seq.empty)
        val successes = outcomes.count(_.isSuccess)
        val failures  = outcomes.count(_.isFailure)
        logger.info(s"Booking complete: $successes succeeded, $failures failed")

        logger.info("Shutting down Resy Booking Bot")
        promise.success(())
      }
    }

    Await.result(promise.future, Duration.Inf)
    system.terminate()
  }
}
