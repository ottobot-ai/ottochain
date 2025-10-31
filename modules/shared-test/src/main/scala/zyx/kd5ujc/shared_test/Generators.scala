package zyx.kd5ujc.shared_test

import cats.data.NonEmptySet
import eu.timepit.refined.scalacheck.numeric._
import eu.timepit.refined.types.numeric.NonNegLong
import org.scalacheck.{Arbitrary, Gen}
import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotStateProof}
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.height.{Height, SubHeight}
import io.constellationnetwork.schema.transaction.RewardTransaction
import io.constellationnetwork.schema.{ActiveTip, BlockAsActiveTip, DeprecatedTip, SnapshotOrdinal, SnapshotTips}
import io.constellationnetwork.security.Hashed
import io.constellationnetwork.security.hash.{Hash, ProofsHash}
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}

import java.math.BigInteger
import scala.collection.immutable.SortedSet

object Generators {

  val genFlightId: Gen[String] = nonEmptyStrGen(6)

  val genTaskId: Gen[String] = for {
    n   <- Gen.chooseNum(1, 15)
    str <- nonEmptyStrGen(n)
  } yield str

  val genSnapshotOrdinal: Gen[SnapshotOrdinal] =
    Arbitrary.arbitrary[NonNegLong].map(SnapshotOrdinal(_))

  val genHeight: Gen[Height] =
    Arbitrary.arbitrary[NonNegLong].map(Height(_))

  val genSubHeight: Gen[SubHeight] =
    Arbitrary.arbitrary[NonNegLong].map(SubHeight(_))

  val genHash: Gen[Hash] =
    Gen.stringOfN(64, Gen.hexChar).map(Hash(_))

  val genEpochProgress: Gen[EpochProgress] =
    Arbitrary.arbitrary[NonNegLong].map(EpochProgress(_))
  val idGen: Gen[Id] = hexGen(128).map(Id(_))

  val signatureGen: Gen[Signature] =
    /* BouncyCastle encodes ECDSA with ASN.1 DER which which is variable length. That generator should be changed to
       fixed length instead of range when we switch to SHA512withPLAIN-ECDSA.
     */
    Gen
      .chooseNum(140, 144)
      .flatMap(hexGen)
      .map(Signature(_))

  val signatureProofGen: Gen[SignatureProof] =
    for {
      id        <- idGen
      signature <- signatureGen
    } yield SignatureProof(id, signature)
  val genProofsHash: Gen[ProofsHash] = genHash.map(h => ProofsHash(h.value))

  val genCurrencySnapshotStateProof: Gen[CurrencySnapshotStateProof] = for {
    lastTxRefsProof <- genHash
    balancesProof   <- genHash
    lastMessagesProof = None
    lastFeeTxRefsProof = None
    lastAllowSpendRefsProof = None
    activeAllowSpends = None
    globalSnapshotSync = None
    lastTokenLockRefsProof = None
    activeTokenLocks = None
  } yield CurrencySnapshotStateProof(
    lastTxRefsProof,
    balancesProof,
    lastMessagesProof,
    lastFeeTxRefsProof,
    lastAllowSpendRefsProof,
    activeAllowSpends,
    globalSnapshotSync,
    lastTokenLockRefsProof,
    activeTokenLocks
  )

  val genCurrencyIncrementalSnapshot: Gen[CurrencyIncrementalSnapshot] = for {
    ordinal         <- genSnapshotOrdinal
    height          <- genHeight
    subHeight       <- genSubHeight
    lastSnpshotHash <- genHash
    blocks = SortedSet.empty[BlockAsActiveTip]
    rewards = SortedSet.empty[RewardTransaction]
    tips = SnapshotTips(SortedSet.empty[DeprecatedTip], SortedSet.empty[ActiveTip])
    stateProof    <- genCurrencySnapshotStateProof
    epochProgress <- genEpochProgress
    dataApplication = None
    messages = None
    globalSnapshotSyncs = None
    feeTransactions = None
    artifacts = None
    allowSpendBlocks = None
    tokenLockBlocks = None
    globalSyncView = None
  } yield CurrencyIncrementalSnapshot(
    ordinal,
    height,
    subHeight,
    lastSnpshotHash,
    blocks,
    rewards,
    tips,
    stateProof,
    epochProgress,
    dataApplication,
    messages,
    globalSnapshotSyncs,
    feeTransactions,
    artifacts,
    allowSpendBlocks,
    tokenLockBlocks,
    globalSyncView
  )

  val genHashedCurrencyIncSnapshot: Gen[Hashed[CurrencyIncrementalSnapshot]] = for {
    snapshot        <- genCurrencyIncrementalSnapshot
    signatureProofs <- signatureProofN(1)
    hash            <- genHash
    proofsHash      <- genProofsHash
  } yield Hashed(
    Signed(snapshot, signatureProofs),
    hash,
    proofsHash
  )

  def nonEmptyStrGen(size: Int): Gen[String] = Gen.buildableOfN[String, Char](size, Gen.alphaNumChar)

  def hexGen(n: Int): Gen[Hex] = Gen.stringOfN(n, Gen.hexChar).map(Hex(_))

  @annotation.tailrec
  def generateValueWithRetry[A](gen: Gen[A], retries: Int = 10): A = gen.sample match {
    case Some(value)         => value
    case None if retries > 0 => generateValueWithRetry(gen, retries - 1)
    case None                => throw new RuntimeException("Failed to generate a value after multiple attempts")
  }

  def signatureProofN(n: Int): Gen[NonEmptySet[SignatureProof]] =
    Gen.listOfN(n, signatureProofGen).map(l => NonEmptySet.fromSetUnsafe(SortedSet.from(l)))

}
