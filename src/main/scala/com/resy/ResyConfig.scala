package com.resy

final case class ResyKeys(apiKey: String, authToken: String)

final case class ReservationDetails(
  date: String,
  partySize: Int,
  venueId: Int,
  resTimeTypes: Seq[ReservationTimeType],
  name: Option[String] = None  // Optional friendly name for logging
)

final case class ReservationTimeType(reservationTime: String, tableType: Option[String] = None)

object ReservationTimeType {

  def apply(reservationTime: String, tableType: String): ReservationTimeType = {
    ReservationTimeType(reservationTime, Some(tableType))
  }
}

final case class SnipeTime(hours: Int, minutes: Int)

// Wrapper for multiple restaurants
final case class MultiReservationDetails(restaurants: Seq[ReservationDetails])

// Bot settings
final case class Settings(dryRun: Boolean = false)
