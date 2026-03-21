package xyz.kd5ujc.avl

import AvlPlusNode._
import AvlPlusHash._

/**
 * Persistent, immutable AVL+ tree.
 *
 * Design principles:
 *   - All key-value data stored in leaves (B+-tree style).
 *   - Internal nodes carry only a routing key and AVL balance factor.
 *   - Two sentinel leaves bound the key space: NegInf (all-zeros) and PosInf (all-0xFF).
 *   - Each operation produces a NEW tree (purely functional / persistent).
 *   - Every node has a cached SHA-256 label (domain-separated hash).
 *
 * Key ordering: unsigned lexicographic (byte-by-byte, treating values as unsigned).
 * Operations: insert (upsert), lookup, delete — all O(log n).
 *
 * Compatible with OttoChain's JsonBinaryCodec via circe Encoder/Decoder on AvlPlusNode.
 *
 * Routing rule for internal nodes with routingKey R:
 *   - key < R  → descend left
 *   - key >= R → descend right
 */
final class AvlPlusTree private (
  val root:      AvlPlusNode,
  val keyLength: Int
) {

  import AvlPlusTree._

  /**
   * 33-byte digest: 32-byte root label hash ++ 1 byte height.
   * Matches Ergo's convention: `root.label ++ rootHeight.toByte`.
   */
  def digest: Array[Byte] = {
    val labelBytes = java.util.HexFormat.of().parseHex(root.label.value)
    labelBytes :+ root.height.toByte
  }

  /**
   * Insert or update a key-value pair.
   * Key must be exactly keyLength bytes long and must not be a sentinel key.
   */
  def insert(key: Array[Byte], value: Array[Byte]): AvlPlusTree = {
    require(key.length == keyLength, s"Key length must be $keyLength, got ${key.length}")
    val (newRoot, _) = insertNode(root, key, value)
    new AvlPlusTree(newRoot, keyLength)
  }

  /**
   * Lookup a key. Returns the value bytes if found, None otherwise.
   * Sentinel keys are never returned.
   */
  def lookup(key: Array[Byte]): Option[Array[Byte]] = {
    require(key.length == keyLength)
    lookupNode(root, key)
  }

  /**
   * Delete a key from the tree.
   * Returns a new tree. If the key is absent or is a sentinel, the tree is unchanged.
   */
  def delete(key: Array[Byte]): AvlPlusTree = {
    require(key.length == keyLength)
    val negInf = Array.fill(keyLength)(0x00.toByte)
    val posInf = Array.fill(keyLength)(0xff.toByte)
    if (compareKeys(key, negInf) == 0 || compareKeys(key, posInf) == 0)
      return this
    val newRoot = deleteNode(root, key)
    new AvlPlusTree(newRoot, keyLength)
  }

  // ─── Insert ────────────────────────────────────────────────────────────────
  // Returns (newNode, leafInserted: Boolean).

  private def insertNode(node: AvlPlusNode, key: Array[Byte], value: Array[Byte]): (AvlPlusNode, Boolean) =
    node match {
      case leaf: Leaf =>
        val cmp = compareKeys(key, leaf.key)
        if (cmp == 0) {
          // Update in-place: same key, new value, preserve nextKey linkage
          (makeLeaf(leaf.key, value, leaf.nextKey), false)
        } else if (cmp < 0) {
          // new key < leaf.key: insert to the LEFT of this leaf
          // New leaf points to this leaf; this leaf's nextKey unchanged
          val newLeaf = makeLeaf(key, value, leaf.key)
          val internal = mkInternal(leaf.key, newLeaf, leaf)
          (avlBalance(internal), true)
        } else {
          // new key > leaf.key: insert to the RIGHT of this leaf
          // This leaf's nextKey changes to new key; new leaf inherits old nextKey
          val updatedOld = makeLeaf(leaf.key, leaf.value, key)
          val newLeaf = makeLeaf(key, value, leaf.nextKey)
          val internal = mkInternal(key, updatedOld, newLeaf)
          (avlBalance(internal), true)
        }

      case internal: Internal =>
        val cmp = compareKeys(key, internal.routingKey)
        if (cmp < 0) {
          val (newLeft, inserted) = insertNode(internal.left, key, value)
          val newNode = mkInternal(internal.routingKey, newLeft, internal.right)
          (if (inserted) avlBalance(newNode) else newNode, inserted)
        } else {
          val (newRight, inserted) = insertNode(internal.right, key, value)
          val newNode = mkInternal(internal.routingKey, internal.left, newRight)
          (if (inserted) avlBalance(newNode) else newNode, inserted)
        }
    }

  // ─── Lookup ────────────────────────────────────────────────────────────────

  private def lookupNode(node: AvlPlusNode, key: Array[Byte]): Option[Array[Byte]] = {
    val negInf = Array.fill(keyLength)(0x00.toByte)
    val posInf = Array.fill(keyLength)(0xff.toByte)
    node match {
      case leaf: Leaf =>
        // Return value only for real keys (not sentinels)
        if (
          compareKeys(leaf.key, key) == 0 &&
          compareKeys(key, negInf) != 0 &&
          compareKeys(key, posInf) != 0
        )
          Some(leaf.value)
        else None
      case internal: Internal =>
        if (compareKeys(key, internal.routingKey) < 0)
          lookupNode(internal.left, key)
        else
          lookupNode(internal.right, key)
    }
  }

  // ─── Delete ────────────────────────────────────────────────────────────────
  //
  // When a target leaf is found as the direct child of an internal node,
  // collapse: replace the internal node with the sibling subtree.
  // Fix the nextKey chain for the rightmost leaf of the left subtree.

  private def deleteNode(node: AvlPlusNode, key: Array[Byte]): AvlPlusNode =
    node match {
      case leaf: Leaf =>
        // We reached a leaf — either key matches (shouldn't happen: handled by parent) or not found
        leaf

      case internal: Internal =>
        val cmp = compareKeys(key, internal.routingKey)
        if (cmp < 0) {
          internal.left match {
            case targetLeaf: Leaf if compareKeys(targetLeaf.key, key) == 0 =>
              // Collapse: remove left child, replace this node with right subtree
              internal.right
            case _ =>
              val newLeft = deleteNode(internal.left, key)
              avlBalance(mkInternal(internal.routingKey, newLeft, internal.right))
          }
        } else {
          internal.right match {
            case targetLeaf: Leaf if compareKeys(targetLeaf.key, key) == 0 =>
              // Collapse: remove right child, replace this node with left subtree.
              // Fix the rightmost leaf of left subtree to skip the deleted leaf.
              updateLastLeafNextKey(internal.left, targetLeaf.nextKey)
            case _ =>
              val newRight = deleteNode(internal.right, key)
              avlBalance(mkInternal(internal.routingKey, internal.left, newRight))
          }
        }
    }

  /** Update the rightmost leaf's nextKey in the given subtree. */
  private def updateLastLeafNextKey(node: AvlPlusNode, newNextKey: Array[Byte]): AvlPlusNode =
    node match {
      case leaf: Leaf =>
        makeLeaf(leaf.key, leaf.value, newNextKey)
      case internal: Internal =>
        val newRight = updateLastLeafNextKey(internal.right, newNextKey)
        mkInternal(internal.routingKey, internal.left, newRight)
    }

  // ─── AVL balancing ─────────────────────────────────────────────────────────

  private def nodeHeight(n: AvlPlusNode): Int = n.height

  private def mkInternal(rk: Array[Byte], left: AvlPlusNode, right: AvlPlusNode): Internal = {
    val h = 1 + math.max(nodeHeight(left), nodeHeight(right))
    val bal = nodeHeight(right) - nodeHeight(left)
    val lbl = internalLabel(bal, left.label, right.label)
    Internal(rk, left, right, bal, lbl, h)
  }

  private def avlBalance(node: AvlPlusNode): AvlPlusNode = node match {
    case leaf: Leaf  => leaf
    case i: Internal => avlBalanceInternal(i)
  }

  private def avlBalanceInternal(node: Internal): AvlPlusNode = node.balance match {
    case b if b <= -2 =>
      node.left match {
        case l: Internal if l.balance <= 0 =>
          rotateRight(node)
        case l: Internal =>
          // Left-right double rotation
          val newLeft = rotateLeft(l)
          rotateRight(mkInternal(node.routingKey, newLeft, node.right))
        case _ => node
      }
    case b if b >= 2 =>
      node.right match {
        case r: Internal if r.balance >= 0 =>
          rotateLeft(node)
        case r: Internal =>
          // Right-left double rotation
          val newRight = rotateRight(r)
          rotateLeft(mkInternal(node.routingKey, node.left, newRight))
        case _ => node
      }
    case _ => node // already balanced
  }

  private def rotateRight(node: Internal): Internal = {
    val pivot = node.left.asInstanceOf[Internal]
    val newRight = mkInternal(node.routingKey, pivot.right, node.right)
    mkInternal(pivot.routingKey, pivot.left, newRight)
  }

  private def rotateLeft(node: Internal): Internal = {
    val pivot = node.right.asInstanceOf[Internal]
    val newLeft = mkInternal(node.routingKey, node.left, pivot.left)
    mkInternal(pivot.routingKey, newLeft, pivot.right)
  }
}

object AvlPlusTree {

  /** Unsigned lexicographic byte comparison. */
  def compareKeys(a: Array[Byte], b: Array[Byte]): Int = {
    val len = math.min(a.length, b.length)
    var i = 0
    while (i < len) {
      val diff = (a(i) & 0xff) - (b(i) & 0xff)
      if (diff != 0) return diff
      i += 1
    }
    a.length - b.length
  }

  /** Build a leaf with computed label. */
  def makeLeaf(key: Array[Byte], value: Array[Byte], nextKey: Array[Byte]): Leaf = {
    val lbl = leafLabel(key, value, nextKey)
    Leaf(key, value, nextKey, lbl)
  }

  /**
   * Create a new empty AVL+ tree with the given key length.
   *
   * Initialises with two sentinel leaves:
   *   NegInf = Array.fill(keyLength)(0x00) — lower bound
   *   PosInf = Array.fill(keyLength)(0xFF) — upper bound
   *
   * All real data keys are inserted between these sentinels.
   */
  def empty(keyLength: Int): AvlPlusTree = {
    val negInf = Array.fill(keyLength)(0x00.toByte)
    val posInf = Array.fill(keyLength)(0xff.toByte)
    val negLeaf = makeLeaf(negInf, Array.emptyByteArray, posInf)
    val posLeaf = makeLeaf(posInf, Array.emptyByteArray, posInf)
    val h = 1
    val bal = 0
    val lbl = internalLabel(bal, negLeaf.label, posLeaf.label)
    val root = Internal(posInf, negLeaf, posLeaf, bal, lbl, h)
    new AvlPlusTree(root, keyLength)
  }

  /**
   * Build a tree from a collection of key-value pairs.
   * All keys must have the same length (keyLength).
   */
  def fromEntries(entries: Seq[(Array[Byte], Array[Byte])], keyLength: Int): AvlPlusTree =
    entries.foldLeft(empty(keyLength)) { case (tree, (k, v)) => tree.insert(k, v) }
}
