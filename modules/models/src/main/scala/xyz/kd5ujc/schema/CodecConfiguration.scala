package xyz.kd5ujc.schema

import io.circe.magnolia.configured.Configuration

/**
 * Shared codec configuration for all OttoChain schema types.
 *
 * `useDefaults = true` tells the magnolia-derived decoders to fall back to
 * Scala default parameter values when a JSON key is absent.  Without this,
 * every `Option[A] = None` or `Set[X] = Set.empty` field must be sent as
 * an explicit `null` / `[]` on the wire — a common source of client-side
 * serialisation bugs.
 *
 * Import `CodecConfiguration._` in any file using
 * `@derive(customizableEncoder, customizableDecoder)`.
 */
object CodecConfiguration {

  implicit val magnoliaConfiguration: Configuration =
    Configuration.default.withDefaults
}
