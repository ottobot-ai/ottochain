package xyz.kd5ujc.avl

import cats.effect.IO

import weaver.SimpleIOSuite

/**
 * AVL+ tree prototype test suite.
 *
 * 17 tests covering:
 *   1. Empty tree creation
 *   2. Insert + lookup round-trip
 *   3. Upsert (update existing key)
 *   4. Lookup of missing key returns None
 *   5. Delete removes key
 *   6. AVL height invariant after 100 inserts
 *   7. Proof generation for existing key
 *   8. Proof verification succeeds for valid proof
 *   9. Proof verification fails for tampered value
 *   10. Proof verification fails for wrong root digest
 *   11. Proof generation fails for missing key
 *   12. Digest changes after insert
 *   13. All entries present after sequential inserts
 *   14. Circe round-trip for AvlPlusNode (leaf)
 *   15. Circe round-trip for AvlPlusInclusionProof
 *   16. 100-entry bulk insert with all proofs valid
 *   17. Root node JSON has expected structure
 */
object AvlPlusSuite extends SimpleIOSuite {

  val keyLen = 8 // 8-byte keys for test simplicity

  def mkKey(n: Int): Array[Byte] = {
    val buf = new Array[Byte](keyLen)
    buf(4) = ((n >> 24) & 0xff).toByte
    buf(5) = ((n >> 16) & 0xff).toByte
    buf(6) = ((n >> 8) & 0xff).toByte
    buf(7) = (n & 0xff).toByte
    buf
  }

  def mkValue(s: String): Array[Byte] = s.getBytes("UTF-8")

  def bytesEq(a: Array[Byte], b: Array[Byte]): Boolean = java.util.Arrays.equals(a, b)

  // ─────────────────────────────────────────────────────────────────────────────
  // T1: Empty tree
  // ─────────────────────────────────────────────────────────────────────────────

  test("T1 — empty tree has 33-byte digest") {
    IO {
      val tree = AvlPlusTree.empty(keyLen)
      expect(tree.digest.length == 33)
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // T2: Insert + lookup
  // ─────────────────────────────────────────────────────────────────────────────

  test("T2 — insert and lookup returns inserted value") {
    IO {
      val key = mkKey(42)
      val value = mkValue("hello")
      val tree = AvlPlusTree.empty(keyLen).insert(key, value)
      val found = tree.lookup(key)
      expect(found.exists(bytesEq(_, value)))
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // T3: Upsert
  // ─────────────────────────────────────────────────────────────────────────────

  test("T3 — inserting same key twice updates the value") {
    IO {
      val key = mkKey(1)
      val v1 = mkValue("first")
      val v2 = mkValue("second")
      val tree = AvlPlusTree.empty(keyLen).insert(key, v1).insert(key, v2)
      expect(tree.lookup(key).exists(bytesEq(_, v2)))
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // T4: Missing key
  // ─────────────────────────────────────────────────────────────────────────────

  test("T4 — lookup of missing key returns None") {
    IO {
      val tree = AvlPlusTree.empty(keyLen).insert(mkKey(10), mkValue("x"))
      expect(tree.lookup(mkKey(99)).isEmpty)
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // T5: Delete
  // ─────────────────────────────────────────────────────────────────────────────

  test("T5 — delete removes key from tree") {
    IO {
      val key = mkKey(5)
      val tree = AvlPlusTree.empty(keyLen).insert(key, mkValue("v")).delete(key)
      expect(tree.lookup(key).isEmpty)
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // T6: Height invariant
  // ─────────────────────────────────────────────────────────────────────────────

  test("T6 — tree height is O(log n) after 100 inserts") {
    IO {
      val tree = (1 to 100).foldLeft(AvlPlusTree.empty(keyLen)) { (t, i) =>
        t.insert(mkKey(i), mkValue(s"v$i"))
      }
      // AVL height bound: at most 1.44 * log2(102) ≈ 10; allow generous bound of 20
      expect(tree.root.height <= 20)
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // T7: Proof generation
  // ─────────────────────────────────────────────────────────────────────────────

  test("T7 — prover generates proof for existing key") {
    IO {
      val key = mkKey(7)
      val tree = AvlPlusTree.empty(keyLen).insert(key, mkValue("seven"))
      val proof = AvlPlusProver.prove(tree, key)
      expect(proof.isRight)
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // T8: Proof verification success
  // ─────────────────────────────────────────────────────────────────────────────

  test("T8 — verifier accepts valid proof and returns correct value") {
    IO {
      val key = mkKey(8)
      val value = mkValue("eight")
      val tree = AvlPlusTree.empty(keyLen).insert(key, value)
      val digest = tree.digest

      AvlPlusProver.prove(tree, key) match {
        case Left(err) => failure(s"Proof generation failed: $err")
        case Right(prf) =>
          AvlPlusVerifier.verify(prf, digest) match {
            case Left(err) => failure(s"Verification failed: $err")
            case Right(rv) => expect(bytesEq(rv, value))
          }
      }
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // T9: Tampered value
  // ─────────────────────────────────────────────────────────────────────────────

  test("T9 — verifier rejects proof with tampered leaf value") {
    IO {
      val key = mkKey(9)
      val value = mkValue("original")
      val tree = AvlPlusTree.empty(keyLen).insert(key, value)
      val digest = tree.digest

      AvlPlusProver.prove(tree, key) match {
        case Left(err) => failure(s"Proof generation failed: $err")
        case Right(proof) =>
          val tampered = proof.copy(
            witness = proof.witness.map {
              case l: AvlPlusCommitment.Leaf => l.copy(value = mkValue("tampered"))
              case other                     => other
            }
          )
          expect(AvlPlusVerifier.verify(tampered, digest).isLeft)
      }
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // T10: Wrong root digest
  // ─────────────────────────────────────────────────────────────────────────────

  test("T10 — verifier rejects proof with wrong root digest") {
    IO {
      val key = mkKey(10)
      val tree = AvlPlusTree.empty(keyLen).insert(key, mkValue("ten"))

      AvlPlusProver.prove(tree, key) match {
        case Left(err) => failure(s"Proof generation failed: $err")
        case Right(proof) =>
          val wrongDigest = Array.fill(33)(0xaa.toByte)
          expect(AvlPlusVerifier.verify(proof, wrongDigest).isLeft)
      }
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // T11: Proof for missing key
  // ─────────────────────────────────────────────────────────────────────────────

  test("T11 — prover returns error for key not in tree") {
    IO {
      val tree = AvlPlusTree.empty(keyLen).insert(mkKey(1), mkValue("one"))
      val proof = AvlPlusProver.prove(tree, mkKey(99))
      expect(proof.isLeft)
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // T12: Digest changes
  // ─────────────────────────────────────────────────────────────────────────────

  test("T12 — digest changes after inserting a new key") {
    IO {
      val t1 = AvlPlusTree.empty(keyLen)
      val t2 = t1.insert(mkKey(12), mkValue("twelve"))
      expect(!java.util.Arrays.equals(t1.digest, t2.digest))
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // T13: All entries present after sequential inserts
  // ─────────────────────────────────────────────────────────────────────────────

  test("T13 — all entries present after sequential inserts") {
    IO {
      val entries = (1 to 10).map(i => mkKey(i) -> mkValue(s"v$i")).toList
      val tree = entries.foldLeft(AvlPlusTree.empty(keyLen)) { case (t, (k, v)) => t.insert(k, v) }
      val allFound = entries.forall { case (k, v) => tree.lookup(k).exists(bytesEq(_, v)) }
      expect(allFound)
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // T14: Circe round-trip for AvlPlusNode
  // ─────────────────────────────────────────────────────────────────────────────

  test("T14 — AvlPlusNode circe round-trip (leaf)") {
    IO {
      import io.circe.syntax._
      import io.circe.parser._
      import AvlPlusNode._
      val leaf = AvlPlusTree.makeLeaf(mkKey(14), mkValue("leaf14"), mkKey(15))
      val json = (leaf: AvlPlusNode).asJson(nodeEncoder).noSpaces
      decode[AvlPlusNode](json)(nodeDecoder) match {
        case Left(err) => failure(s"Decode failed: $err")
        case Right(decoded: AvlPlusNode.Leaf) =>
          expect(bytesEq(decoded.key, leaf.key)) and
          expect(bytesEq(decoded.value, leaf.value)) and
          expect(decoded.label == leaf.label)
        case Right(other) => failure(s"Expected Leaf, got $other")
      }
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // T15: Circe round-trip for AvlPlusInclusionProof
  // ─────────────────────────────────────────────────────────────────────────────

  test("T15 — AvlPlusInclusionProof circe round-trip (proof re-verifies after decode)") {
    IO {
      import io.circe.syntax._
      import io.circe.parser._
      val key = mkKey(15)
      val tree = AvlPlusTree.empty(keyLen).insert(key, mkValue("fifteen"))
      val digest = tree.digest

      AvlPlusProver.prove(tree, key) match {
        case Left(err) => failure(s"Proof generation failed: $err")
        case Right(proof) =>
          val json = proof.asJson(AvlPlusInclusionProof.encoder).noSpaces
          decode[AvlPlusInclusionProof](json)(AvlPlusInclusionProof.decoder) match {
            case Left(err) => failure(s"Decode failed: $err")
            case Right(decoded) =>
              expect(AvlPlusVerifier.verify(decoded, digest).isRight)
          }
      }
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // T16: Bulk insert with all proofs valid
  // ─────────────────────────────────────────────────────────────────────────────

  test("T16 — 100-entry bulk insert: all proofs verify correctly") {
    IO {
      val entries = (1 to 100).toList.map(i => mkKey(i) -> mkValue(s"value-$i"))
      val tree = entries.foldLeft(AvlPlusTree.empty(keyLen)) { case (t, (k, v)) => t.insert(k, v) }
      val digest = tree.digest

      val failures = entries.flatMap { case (k, v) =>
        AvlPlusProver.prove(tree, k) match {
          case Left(err) =>
            Some(s"proof gen failed for ${k.map("%02x".format(_)).mkString}: $err")
          case Right(prf) =>
            AvlPlusVerifier.verify(prf, digest) match {
              case Left(err) =>
                Some(s"verify failed for ${k.map("%02x".format(_)).mkString}: $err")
              case Right(rv) if !bytesEq(rv, v) =>
                Some(s"value mismatch for ${k.map("%02x".format(_)).mkString}")
              case _ => None
            }
        }
      }

      if (failures.nonEmpty)
        failure(s"${failures.length} failures:\n${failures.take(5).mkString("\n")}")
      else
        success
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // T17: JSON structure
  // ─────────────────────────────────────────────────────────────────────────────

  test("T17 — root node JSON has expected OttoChain-compatible structure") {
    IO {
      import io.circe.syntax._
      import AvlPlusNode._
      val tree = AvlPlusTree.empty(keyLen).insert(mkKey(17), mkValue("seventeen"))
      val json = tree.root.asJson(nodeEncoder)
      expect(json.isObject) and
      expect(json.hcursor.downField("type").as[String].isRight) and
      expect(json.hcursor.downField("label").as[String].isRight)
    }
  }
}
