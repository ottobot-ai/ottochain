package xyz.kd5ujc.avl

import io.constellationnetwork.security.hash.Hash

/**
 * Hashing utilities for AVL+ tree nodes.
 *
 * Domain-separated hashing prevents second-preimage attacks:
 *   Leaf   prefix = 0x00
 *   Internal prefix = 0x01
 *
 * Uses SHA-256 (via `Hash.fromBytes`) which is already used throughout OttoChain.
 */
object AvlPlusHash {

  val LeafPrefix: Byte = 0x00
  val InternalPrefix: Byte = 0x01

  /**
   * Compute the label for a leaf node.
   *
   * leaf_label = SHA256(0x00 ++ key ++ value ++ nextKey)
   */
  def leafLabel(key: Array[Byte], value: Array[Byte], nextKey: Array[Byte]): Hash = {
    val data = Array(LeafPrefix) ++ key ++ value ++ nextKey
    Hash.fromBytes(data)
  }

  /**
   * Compute the label for an internal node.
   *
   * internal_label = SHA256(0x01 ++ balance_byte ++ left.label_bytes ++ right.label_bytes)
   */
  def internalLabel(balance: Int, leftLabel: Hash, rightLabel: Hash): Hash = {
    val balanceByte = (balance + 1).toByte // -1→0, 0→1, +1→2
    val leftBytes = java.util.HexFormat.of().parseHex(leftLabel.value)
    val rightBytes = java.util.HexFormat.of().parseHex(rightLabel.value)
    val data = Array(InternalPrefix, balanceByte) ++ leftBytes ++ rightBytes
    Hash.fromBytes(data)
  }
}
