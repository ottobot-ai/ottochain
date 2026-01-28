package xyz.kd5ujc.schema.fiber

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(encoder, decoder)
case class EventType(value: String) extends AnyVal
