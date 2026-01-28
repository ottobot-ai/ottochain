package xyz.kd5ujc.schema.fiber

import derevo.circe.magnolia.{decoder, encoder, keyDecoder, keyEncoder}
import derevo.derive

@derive(encoder, decoder, keyEncoder, keyDecoder)
case class StateId(value: String) extends AnyVal
