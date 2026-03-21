package xyz.kd5ujc.avl

import AvlPlusHash._
import AvlPlusCommitment._

/**
 * Proof verifier for AVL+ tree inclusion proofs.
 *
 * The verifier only needs:
 *   - The expected 33-byte root digest (32 bytes hash ++ 1 byte height).
 *   - The proof (witness list + key).
 *
 * It does NOT need the full tree — only the proof and digest.
 *
 * Verification algorithm:
 *   1. Extract the leaf commitment from the END of the witness list.
 *   2. Compute leaf_hash = SHA256(0x00 ++ key ++ value ++ nextKey).
 *   3. Walk the witness list in REVERSE (leaf → root), at each internal commitment:
 *      - If sibling is right: parent = Hash(0x01 ++ balance ++ current ++ sibling)
 *      - If sibling is left:  parent = Hash(0x01 ++ balance ++ sibling ++ current)
 *   4. The reconstructed root hash must equal the first 32 bytes of expectedDigest.
 */
object AvlPlusVerifier {

  sealed trait VerificationError

  final case class DigestMismatch(expected: String, actual: String) extends VerificationError {
    override def toString: String = s"DigestMismatch(expected=$expected, actual=$actual)"
  }

  final case class InvalidWitness(msg: String) extends VerificationError {
    override def toString: String = s"InvalidWitness($msg)"
  }

  final case class LeafKeyMismatch(proofKey: String, leafKey: String) extends VerificationError {
    override def toString: String = s"LeafKeyMismatch(proofKey=$proofKey, leafKey=$leafKey)"
  }

  final case class InvalidDigestLength(len: Int) extends VerificationError {
    override def toString: String = s"InvalidDigestLength($len, expected 33)"
  }

  /**
   * Verify an inclusion proof against an expected root digest.
   *
   * @param proof          The inclusion proof to verify.
   * @param expectedDigest 33-byte digest: 32-byte SHA-256 root hash ++ height byte.
   * @return Right(leafValue) if valid, Left(VerificationError) otherwise.
   */
  def verify(
    proof:          AvlPlusInclusionProof,
    expectedDigest: Array[Byte]
  ): Either[VerificationError, Array[Byte]] = {

    if (expectedDigest.length != 33)
      return Left(InvalidDigestLength(expectedDigest.length))

    val expectedRootHash = io.constellationnetwork.security.hash.Hash(
      java.util.HexFormat.of().formatHex(expectedDigest.take(32))
    )

    if (proof.witness.isEmpty)
      return Left(InvalidWitness("Empty witness list"))

    // The witness is ordered root-to-leaf; we reverse to process leaf-to-root
    val reversed = proof.witness.reverse

    // The last element (first in reversed) must be a Leaf commitment
    reversed.head match {
      case leafCommit: Leaf =>
        // Verify the key in the proof matches the leaf commitment
        if (!java.util.Arrays.equals(leafCommit.key, proof.key))
          return Left(
            LeafKeyMismatch(
              hexStr(proof.key),
              hexStr(leafCommit.key)
            )
          )

        // Start with leaf hash
        val leafHash = leafLabel(leafCommit.key, leafCommit.value, leafCommit.nextKey)

        // Walk internal commitments (the rest of the reversed list) to reconstruct root
        val result = reversed.tail.foldLeft[Either[VerificationError, io.constellationnetwork.security.hash.Hash]](
          Right(leafHash)
        ) {
          case (Left(e), _) => Left(e)
          case (Right(currentHash), ic: Internal) =>
            val (leftHash, rightHash) =
              if (ic.siblingIsRight)
                (currentHash, ic.siblingDigest) // we came from left → sibling is on right
              else
                (ic.siblingDigest, currentHash) // we came from right → sibling is on left

            Right(internalLabel(ic.balance, leftHash, rightHash))

          case (_, unexpected) =>
            Left(InvalidWitness(s"Expected Internal commitment in non-leaf position, got: $unexpected"))
        }

        result.flatMap { reconstructedRoot =>
          if (reconstructedRoot == expectedRootHash)
            Right(leafCommit.value)
          else
            Left(DigestMismatch(expectedRootHash.value, reconstructedRoot.value))
        }

      case other =>
        Left(InvalidWitness(s"Expected Leaf at end of witness, got: $other"))
    }
  }

  private def hexStr(bytes: Array[Byte]): String = bytes.map("%02x".format(_)).mkString
}
