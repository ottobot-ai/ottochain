package xyz.kd5ujc.avl

import AvlPlusTree.compareKeys

/**
 * Proof generator for AVL+ trees.
 *
 * Produces inclusion proofs for a given key by walking from the root to the leaf,
 * recording at each internal node the sibling's digest.
 *
 * The resulting witness list is ordered root-to-leaf (top-to-bottom), so the verifier
 * can process it in the same order (reversed for bottom-up reconstruction).
 */
object AvlPlusProver {

  sealed trait ProofError

  final case class KeyNotFound(key: Array[Byte]) extends ProofError {
    override def toString: String = s"KeyNotFound(${key.map("%02x".format(_)).mkString})"
  }
  final case class TreeMalformed(msg: String) extends ProofError

  /**
   * Generate an inclusion proof for `key` in `tree`.
   *
   * Returns Right(proof) if the key exists (non-sentinel leaf found),
   * or Left(error) otherwise.
   */
  def prove(tree: AvlPlusTree, key: Array[Byte]): Either[ProofError, AvlPlusInclusionProof] =
    proveNode(tree.root, key, acc = Nil)
      .map(witness => AvlPlusInclusionProof(key, witness))

  private def proveNode(
    node: AvlPlusNode,
    key:  Array[Byte],
    acc:  List[AvlPlusCommitment]
  ): Either[ProofError, List[AvlPlusCommitment]] =
    node match {
      case leaf: AvlPlusNode.Leaf =>
        if (compareKeys(leaf.key, key) == 0)
          Right(acc :+ AvlPlusCommitment.Leaf(leaf.key, leaf.value, leaf.nextKey))
        else
          Left(KeyNotFound(key))

      case internal: AvlPlusNode.Internal =>
        val cmp = compareKeys(key, internal.routingKey)
        if (cmp < 0) {
          // go left — sibling is right child
          val commitment = AvlPlusCommitment.Internal(
            routingKey = internal.routingKey,
            siblingDigest = internal.right.label,
            siblingIsRight = true,
            balance = internal.balance
          )
          proveNode(internal.left, key, acc :+ commitment)
        } else {
          // go right — sibling is left child
          val commitment = AvlPlusCommitment.Internal(
            routingKey = internal.routingKey,
            siblingDigest = internal.left.label,
            siblingIsRight = false,
            balance = internal.balance
          )
          proveNode(internal.right, key, acc :+ commitment)
        }
    }
}
