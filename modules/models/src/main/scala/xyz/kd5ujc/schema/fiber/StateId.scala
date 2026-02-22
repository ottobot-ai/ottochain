package xyz.kd5ujc.schema.fiber

import xyz.kd5ujc.schema.CodecConfiguration._

import derevo.circe.magnolia.{customizableDecoder, customizableEncoder, keyDecoder, keyEncoder}
import derevo.derive

@derive(customizableEncoder, customizableDecoder, keyEncoder, keyDecoder)
case class StateId(value: String) extends AnyVal
