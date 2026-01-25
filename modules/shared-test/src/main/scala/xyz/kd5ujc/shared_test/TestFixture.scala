package xyz.kd5ujc.shared_test

import cats.effect.{IO, Resource}

import io.constellationnetwork.currency.dataApplication.{L0NodeContext, L1NodeContext}
import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.SecurityProvider

import xyz.kd5ujc.shared_test.Mock.{MockL0NodeContext, MockL1NodeContext}
import xyz.kd5ujc.shared_test.Participant._

/**
 * Shared test fixture providing common test dependencies.
 *
 * Use `TestFixture.resource` to acquire all dependencies in a Resource:
 * {{{
 * TestFixture.resource.use { fixture =>
 *   implicit val s = fixture.securityProvider
 *   implicit val l0ctx = fixture.l0Context
 *   implicit val l1ctx = fixture.l1Context
 *   // Use fixture.registry, fixture.ordinal, etc.
 *   // Create Combiner with: Combiner.make[IO].pure[IO]
 * }
 * }}}
 *
 * Override participants if needed:
 * {{{
 * TestFixture.resource(Set(Alice, Bob, Charlie)).use { ... }
 * }}}
 *
 * Note: Combiner is not included in the fixture because it lives in shared-data
 * which depends on shared-test (would create circular dependency). Tests should
 * create Combiner inline: `combiner <- Combiner.make[IO].pure[IO]`
 */
final case class TestFixture(
  securityProvider: SecurityProvider[IO],
  l0Context:        L0NodeContext[IO],
  l1Context:        L1NodeContext[IO],
  registry:         ParticipantRegistry[IO],
  ordinal:          SnapshotOrdinal
)

object TestFixture {

  /**
   * Creates a Resource that provides a fully initialized TestFixture.
   *
   * @param participants Set of participants to register (defaults to Alice and Bob)
   * @return Resource containing the test fixture
   */
  def resource(
    participants: Set[Participant] = Set(Alice, Bob)
  ): Resource[IO, TestFixture] =
    SecurityProvider.forAsync[IO].evalMap { implicit securityProvider =>
      for {
        l0ctx    <- MockL0NodeContext.make[IO]
        l1ctx    <- MockL1NodeContext.make[IO]
        registry <- ParticipantRegistry.create[IO](participants)
        ordinal  <- l0ctx.getLastCurrencySnapshot.map(_.map(_.ordinal.next).get)
      } yield TestFixture(
        securityProvider = securityProvider,
        l0Context = l0ctx,
        l1Context = l1ctx,
        registry = registry,
        ordinal = ordinal
      )
    }
}
