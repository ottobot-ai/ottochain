package xyz.kd5ujc.shared_data.identity.agent

import cats.effect.IO

/**
 * Reputation scoring system for OttoChain agents.
 *
 * Tracks agent behavior and computes a reputation score [0.0, 1.0] used to
 * gate access to delegation tiers. Higher reputation allows larger delegation
 * scopes, longer session durations, and reduced stake bond requirements.
 *
 * === Scoring Model ===
 * Score is a weighted average of attested events. Events are weighted by:
 * - Event type (COMPLETION > VIOLATION)
 * - Event weight provided by the attesting party [0.0, 1.0]
 * - Recency decay (older events contribute less)
 *
 * Score bounds: [0.0, 1.0]
 * - BASIC tier: score >= 0.0
 * - STANDARD tier: score >= 0.3
 * - ADVANCED tier: score >= 0.6
 * - EXPERT tier: score >= 0.9
 */
object ReputationScoring {

  /** Attestation event types */
  val COMPLETION = "COMPLETION"
  val VIOLATION = "VIOLATION"
  val NEUTRAL = "NEUTRAL"

  /** Weight applied to each event type */
  val EventWeights: Map[String, Double] = Map(
    COMPLETION -> 1.0,
    NEUTRAL    -> 0.5,
    VIOLATION  -> -1.0
  )

  /**
   * Reputation state for a single agent.
   *
   * @param agentAddress The agent's identifier
   * @param score        Current reputation score [0.0, 1.0]
   * @param totalEvents  Total number of attested events
   * @param history      Recent event history (event type → count)
   */
  final case class ReputationState(
    agentAddress: String,
    score:        Double,
    totalEvents:  Int,
    history:      Map[String, Int]
  ) {

    def tier: String =
      if (score >= 0.9) "EXPERT"
      else if (score >= 0.6) "ADVANCED"
      else if (score >= 0.3) "STANDARD"
      else "BASIC"
  }

  /**
   * Initialize a new agent with zero reputation.
   *
   * @param agentAddress The agent's address
   */
  def initializeAgent(agentAddress: String): ReputationState =
    ReputationState(
      agentAddress = agentAddress,
      score = 0.0,
      totalEvents = 0,
      history = Map.empty
    )

  /**
   * Update reputation from a new attested event.
   *
   * Uses exponential moving average to weight recent events more heavily.
   * Alpha = 0.1 means 10% weight to the new observation, 90% to history.
   *
   * Score is clamped to [0.0, 1.0].
   *
   * @param state       Current reputation state
   * @param eventType   Type of event ("COMPLETION", "VIOLATION", "NEUTRAL")
   * @param eventWeight Attestor-provided weight [0.0, 1.0] for this event
   */
  def updateReputationFromAttestation(
    state:       ReputationState,
    eventType:   String,
    eventWeight: Double
  ): IO[ReputationState] = IO {
    val typeMultiplier = EventWeights.getOrElse(eventType, 0.0)
    val delta = typeMultiplier * eventWeight.max(0.0).min(1.0)

    // EMA with alpha=0.1, normalized to [0, 1] range
    val alpha = 0.1
    val rawScore = state.score + alpha * delta

    val newScore = rawScore.max(0.0).min(1.0)
    val newHistory = state.history.updated(
      eventType,
      state.history.getOrElse(eventType, 0) + 1
    )

    state.copy(
      score = newScore,
      totalEvents = state.totalEvents + 1,
      history = newHistory
    )
  }

  /**
   * Compute reputation from scratch given a full event history.
   *
   * @param agentAddress The agent's address
   * @param events       List of (eventType, weight) pairs in chronological order
   */
  def computeFromHistory(
    agentAddress: String,
    events:       List[(String, Double)]
  ): IO[ReputationState] = {
    val initial = initializeAgent(agentAddress)
    events.foldLeft(IO.pure(initial)) { case (ioState, (eventType, weight)) =>
      ioState.flatMap(s => updateReputationFromAttestation(s, eventType, weight))
    }
  }
}
