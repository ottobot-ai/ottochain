package zyx.kd5ujc.shared_test

import cats.effect.Sync
import cats.syntax.applicative._
import cats.syntax.option._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.dataApplication.L0NodeContext
import io.constellationnetwork.currency.schema.currency
import io.constellationnetwork.domain.seedlist.SeedlistEntry
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.swap.CurrencyId
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hashed, SecurityProvider}

import Generators.{genHashedCurrencyIncSnapshot, generateValueWithRetry}

object Mock {

  trait MockL0NodeContext[F[_]] extends L0NodeContext[F]

  object MockL0NodeContext {

    def make[F[_]: Sync]: F[MockL0NodeContext[F]] = {
      val currencySnapshot = generateValueWithRetry(genHashedCurrencyIncSnapshot).some

      Sync[F].delay(
        new MockL0NodeContext[F] {

          def getLastCurrencySnapshot: F[Option[Hashed[currency.CurrencyIncrementalSnapshot]]] =
            currencySnapshot.pure[F]

          def getCurrencySnapshot(
            ordinal: SnapshotOrdinal
          ): F[Option[Hashed[currency.CurrencyIncrementalSnapshot]]] = ???

          def getLastCurrencySnapshotCombined
            : F[Option[(Hashed[currency.CurrencyIncrementalSnapshot], currency.CurrencySnapshotInfo)]] = ???

          def securityProvider: SecurityProvider[F] = ???

          def getCurrencyId: F[CurrencyId] = ???

          def getMetagraphL0Seedlist: Option[Set[SeedlistEntry]] = None

          def getLastSynchronizedAllowSpends
            : F[Option[SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[swap.AllowSpend]]]]]] = ???

          def getLastSynchronizedTokenLocks: F[Option[SortedMap[Address, SortedSet[Signed[tokenLock.TokenLock]]]]] = ???

          def getLastSynchronizedGlobalSnapshot: F[Option[GlobalIncrementalSnapshot]] = ???

          def getLastSynchronizedGlobalSnapshotCombined: F[Option[(GlobalIncrementalSnapshot, GlobalSnapshotInfo)]] =
            ???
        }
      )
    }
  }

}
