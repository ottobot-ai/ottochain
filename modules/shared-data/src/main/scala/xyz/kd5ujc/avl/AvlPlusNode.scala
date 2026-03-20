package xyz.kd5ujc.avl

import io.constellationnetwork.security.hash.Hash

import io.circe.syntax._
import io.circe.{Decoder, Encoder}

/**
 * AVL+ tree node types.
 *
 * An AVL+ tree is a balanced BST where:
 *   - All key-value data lives exclusively in leaves.
 *   - Internal nodes store only a routing key (smallest key in right subtree) and balance factor.
 *   - Every node has a label (hash digest) for authenticated proofs.
 *   - Two sentinel leaves (-∞ and +∞) simplify boundary cases.
 *
 * Hashing scheme (domain-separated to prevent second-preimage attacks):
 *   - Leaf   label = Hash(0x00 ++ key ++ value_bytes ++ nextLeafKey)
 *   - Internal label = Hash(0x01 ++ balance_byte ++ left.label ++ right.label)
 *
 * Compatible with OttoChain's circe serialization and JsonBinaryCodec.
 */
sealed trait AvlPlusNode {

  /** The cached hash digest of this node. */
  def label: Hash

  /** Balance factor: -1 (left-heavy), 0 (balanced), +1 (right-heavy). */
  def balance: Int

  /** Height of the subtree rooted at this node. */
  def height: Int
}

object AvlPlusNode {

  /**
   * A leaf node — holds the actual key-value data.
   *
   * @param key        The byte key for this leaf.
   * @param value      The value stored at this leaf (serialized bytes).
   * @param nextKey    The key of the next leaf in ascending order (linked-list pointer).
   * @param label      The cached hash label.
   */
  final case class Leaf(
    key:     Array[Byte],
    value:   Array[Byte],
    nextKey: Array[Byte],
    label:   Hash
  ) extends AvlPlusNode {
    val balance: Int = 0
    val height: Int = 0
  }

  /**
   * An internal (routing) node — holds only a routing key and balance, no data.
   *
   * @param routingKey The smallest key in the right subtree.
   * @param left       Left subtree.
   * @param right      Right subtree.
   * @param balance    AVL balance factor.
   * @param label      The cached hash label.
   * @param height     Height of this subtree.
   */
  final case class Internal(
    routingKey: Array[Byte],
    left:       AvlPlusNode,
    right:      AvlPlusNode,
    balance:    Int,
    label:      Hash,
    height:     Int
  ) extends AvlPlusNode

  // ─── Helpers ─────────────────────────────────────────────────────────────────

  def bytesToHex(bytes: Array[Byte]): String = bytes.map("%02x".format(_)).mkString

  def hexToBytes(hex: String): Array[Byte] =
    if (hex.isEmpty) Array.emptyByteArray
    else hex.grouped(2).map(s => Integer.parseInt(s, 16).toByte).toArray

  // ─── Circe codecs ────────────────────────────────────────────────────────────

  implicit val hashEncoder: Encoder[Hash] =
    Encoder.encodeString.contramap(_.value)

  implicit val hashDecoder: Decoder[Hash] =
    Decoder.decodeString.map(Hash(_))

  implicit val leafEncoder: Encoder[Leaf] = Encoder.instance { l =>
    import io.circe.Json
    Json.obj(
      "type"    -> "Leaf".asJson,
      "key"     -> bytesToHex(l.key).asJson,
      "value"   -> bytesToHex(l.value).asJson,
      "nextKey" -> bytesToHex(l.nextKey).asJson,
      "label"   -> l.label.asJson
    )
  }

  // Forward reference for recursive structure — must be lazy
  implicit lazy val nodeEncoder: Encoder[AvlPlusNode] = Encoder.instance {
    case l: Leaf     => l.asJson(leafEncoder)
    case i: Internal => i.asJson(internalEncoder)
  }

  implicit lazy val internalEncoder: Encoder[Internal] = Encoder.instance { i =>
    import io.circe.Json
    Json.obj(
      "type"       -> "Internal".asJson,
      "routingKey" -> bytesToHex(i.routingKey).asJson,
      "left"       -> i.left.asJson(nodeEncoder),
      "right"      -> i.right.asJson(nodeEncoder),
      "balance"    -> i.balance.asJson,
      "label"      -> i.label.asJson,
      "height"     -> i.height.asJson
    )
  }

  implicit lazy val leafDecoder: Decoder[Leaf] = Decoder.instance { c =>
    for {
      key     <- c.downField("key").as[String].map(hexToBytes)
      value   <- c.downField("value").as[String].map(hexToBytes)
      nextKey <- c.downField("nextKey").as[String].map(hexToBytes)
      label   <- c.downField("label").as[Hash]
    } yield Leaf(key, value, nextKey, label)
  }

  implicit lazy val nodeDecoder: Decoder[AvlPlusNode] = Decoder.instance { c =>
    c.downField("type").as[String].flatMap {
      case "Leaf"     => c.as[Leaf](leafDecoder)
      case "Internal" => c.as[Internal](internalDecoder)
      case t          => Left(io.circe.DecodingFailure(s"Unknown node type: $t", c.history))
    }
  }

  implicit lazy val internalDecoder: Decoder[Internal] = Decoder.instance { c =>
    for {
      rk      <- c.downField("routingKey").as[String].map(hexToBytes)
      left    <- c.downField("left").as[AvlPlusNode](nodeDecoder)
      right   <- c.downField("right").as[AvlPlusNode](nodeDecoder)
      balance <- c.downField("balance").as[Int]
      label   <- c.downField("label").as[Hash]
      height  <- c.downField("height").as[Int]
    } yield Internal(rk, left, right, balance, label, height)
  }
}
