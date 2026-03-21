package xyz.kd5ujc.avl

import io.constellationnetwork.security.hash.Hash

import io.circe.syntax._
import io.circe.{Decoder, Encoder}

/**
 * Inclusion proof for an AVL+ tree.
 *
 * A proof demonstrates that a specific key exists in the tree at the time
 * the tree had a known root digest.
 *
 * The witness is a list of `AvlPlusCommitment` values from root to leaf:
 *   - InternalCommitment: records the sibling subtree's digest at each routing node.
 *   - LeafCommitment: records the target leaf.
 *
 * Verification walks the witness from leaf to root (reverse), reconstructing
 * hashes bottom-up and confirming the final hash equals the root digest.
 */
final case class AvlPlusInclusionProof(
  key:     Array[Byte],
  witness: List[AvlPlusCommitment]
)

sealed trait AvlPlusCommitment

object AvlPlusCommitment {

  /**
   * Commitment for an internal node on the proof path.
   *
   * @param routingKey     The routing key at this internal node.
   * @param siblingDigest  Hash of the sibling subtree (the branch NOT taken).
   * @param siblingIsRight True if the sibling is the right child (we went left).
   * @param balance        Balance factor of this internal node.
   */
  final case class Internal(
    routingKey:     Array[Byte],
    siblingDigest:  Hash,
    siblingIsRight: Boolean,
    balance:        Int
  ) extends AvlPlusCommitment

  /**
   * Commitment for the leaf at the end of the proof path.
   *
   * @param key     The leaf's key.
   * @param value   The leaf's value.
   * @param nextKey The leaf's nextKey pointer.
   */
  final case class Leaf(
    key:     Array[Byte],
    value:   Array[Byte],
    nextKey: Array[Byte]
  ) extends AvlPlusCommitment

  // ─── Circe codecs ────────────────────────────────────────────────────────────

  implicit val hashEncoder: Encoder[Hash] = Encoder.encodeString.contramap(_.value)
  implicit val hashDecoder: Decoder[Hash] = Decoder.decodeString.map(Hash(_))

  implicit val internalEncoder: Encoder[Internal] = Encoder.instance { c =>
    import io.circe.Json
    Json.obj(
      "type"           -> "Internal".asJson,
      "routingKey"     -> AvlPlusNode.bytesToHex(c.routingKey).asJson,
      "siblingDigest"  -> c.siblingDigest.asJson,
      "siblingIsRight" -> c.siblingIsRight.asJson,
      "balance"        -> c.balance.asJson
    )
  }

  implicit val leafEncoder: Encoder[Leaf] = Encoder.instance { c =>
    import io.circe.Json
    Json.obj(
      "type"    -> "Leaf".asJson,
      "key"     -> AvlPlusNode.bytesToHex(c.key).asJson,
      "value"   -> AvlPlusNode.bytesToHex(c.value).asJson,
      "nextKey" -> AvlPlusNode.bytesToHex(c.nextKey).asJson
    )
  }

  implicit val commitmentEncoder: Encoder[AvlPlusCommitment] = Encoder.instance {
    case i: Internal => i.asJson(internalEncoder)
    case l: Leaf     => l.asJson(leafEncoder)
  }

  implicit val internalDecoder: Decoder[Internal] = Decoder.instance { c =>
    for {
      rk  <- c.downField("routingKey").as[String].map(AvlPlusNode.hexToBytes)
      sd  <- c.downField("siblingDigest").as[Hash]
      sir <- c.downField("siblingIsRight").as[Boolean]
      bal <- c.downField("balance").as[Int]
    } yield Internal(rk, sd, sir, bal)
  }

  implicit val leafDecoder: Decoder[Leaf] = Decoder.instance { c =>
    for {
      key     <- c.downField("key").as[String].map(AvlPlusNode.hexToBytes)
      value   <- c.downField("value").as[String].map(AvlPlusNode.hexToBytes)
      nextKey <- c.downField("nextKey").as[String].map(AvlPlusNode.hexToBytes)
    } yield Leaf(key, value, nextKey)
  }

  implicit val commitmentDecoder: Decoder[AvlPlusCommitment] = Decoder.instance { c =>
    c.downField("type").as[String].flatMap {
      case "Internal" => c.as[Internal](internalDecoder)
      case "Leaf"     => c.as[Leaf](leafDecoder)
      case t          => Left(io.circe.DecodingFailure(s"Unknown commitment type: $t", c.history))
    }
  }
}

object AvlPlusInclusionProof {

  implicit val encoder: Encoder[AvlPlusInclusionProof] = Encoder.instance { p =>
    import io.circe.Json
    Json.obj(
      "key"     -> AvlPlusNode.bytesToHex(p.key).asJson,
      "witness" -> p.witness.asJson(io.circe.Encoder.encodeList(AvlPlusCommitment.commitmentEncoder))
    )
  }

  implicit val decoder: Decoder[AvlPlusInclusionProof] = Decoder.instance { c =>
    for {
      key <- c.downField("key").as[String].map(AvlPlusNode.hexToBytes)
      witness <- c
        .downField("witness")
        .as[List[AvlPlusCommitment]](
          io.circe.Decoder.decodeList(AvlPlusCommitment.commitmentDecoder)
        )
    } yield AvlPlusInclusionProof(key, witness)
  }
}
