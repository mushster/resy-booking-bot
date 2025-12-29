package com.resy

import org.apache.logging.log4j.scala.Logging

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object Metrics extends Logging {

  /** Wraps a Future with timing metrics, logging the duration on completion.
    * @param operation
    *   Name of the operation being timed (e.g., "find", "details", "book")
    * @param restaurant
    *   Name of the restaurant for log correlation
    * @param f
    *   The Future to time
    * @return
    *   The same Future, with timing logged on completion
    */
  def timed[T](operation: String, restaurant: String)(
    f: => Future[T]
  )(implicit ec: ExecutionContext): Future[T] = {
    val start = System.currentTimeMillis()
    f.andThen {
      case Success(_) =>
        val elapsed = System.currentTimeMillis() - start
        logger.info(s"[$restaurant] $operation completed in ${elapsed}ms")
      case Failure(e) =>
        val elapsed = System.currentTimeMillis() - start
        logger.warn(s"[$restaurant] $operation failed after ${elapsed}ms: ${e.getMessage}")
    }
  }

  /** Logs the total workflow time for a restaurant booking attempt.
    */
  def timedWorkflow[T](restaurant: String)(
    f: => Future[T]
  )(implicit ec: ExecutionContext): Future[T] = {
    val start = System.currentTimeMillis()
    f.andThen {
      case Success(_) =>
        val elapsed = System.currentTimeMillis() - start
        logger.info(s"[$restaurant] TOTAL WORKFLOW completed in ${elapsed}ms")
      case Failure(e) =>
        val elapsed = System.currentTimeMillis() - start
        logger.warn(s"[$restaurant] TOTAL WORKFLOW failed after ${elapsed}ms: ${e.getMessage}")
    }
  }
}
