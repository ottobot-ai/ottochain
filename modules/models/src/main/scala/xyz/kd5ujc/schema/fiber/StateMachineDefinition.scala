package xyz.kd5ujc.schema.fiber

import io.constellationnetwork.metagraph_sdk.json_logic.JsonLogicValue

import xyz.kd5ujc.schema.CodecConfiguration._

import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
import derevo.derive

@derive(customizableEncoder, customizableDecoder)
case class StateMachineDefinition(
  states:       Map[StateId, State],
  initialState: StateId,
  transitions:  List[Transition],
  metadata:     Option[JsonLogicValue] = None
) {

  // Helper to get transitions by current state + event type
  // Returns list to support multiple transitions with guards (first-match-wins)
  lazy val transitionMap: Map[(StateId, String), List[Transition]] =
    transitions.groupBy(t => (t.from, t.eventName))
}
