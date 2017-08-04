package com.overviewdocs.models

import scala.collection.immutable

/** A matcher for document IDs.
  *
  * This assumes all document IDs are generated by ANDing a 32-bit document-set
  * ID with a 32-bit document _number_ within the document set. (We'll call that
  * number a "lower ID" in this class.)
  *
  * It's small enough to send over the wire. A 10M-document docset will have a
  * 10M-element bitset, which is 1.25MB.
  *
  * There are specially-optimized classes for `All(documentSetId)` and `Empty`.
  */
sealed trait DocumentIdFilter {
  /** `true` iff the document matches this filter. */
  def contains(documentId: Long): Boolean

  /** Document IDs that match both DocumentIdSets.
    */
  def intersect(documentIdFilter: DocumentIdFilter): DocumentIdFilter
}

object DocumentIdFilter {
  case object Empty extends DocumentIdFilter {
    override def contains(documentId: Long) = false
    override def intersect(that: DocumentIdFilter) = this
  }

  case class All(documentSetId: Int) extends DocumentIdFilter {
    override def contains(documentId: Long) = {
      (documentId >> 32).toInt == documentSetId
    }

    override def intersect(that: DocumentIdFilter) = that match {
      case All(thatDocumentSetId) if documentSetId == thatDocumentSetId => this
      case DocumentIdSet(thatDocumentSetId, _) if documentSetId == thatDocumentSetId => that
      case _ => Empty
    }
  }
}

/** A set of document IDs.
  *
  * This assumes all document IDs are generated by ANDing a 32-bit document-set
  * ID with a 32-bit document _number_ within the document set. (We'll call that
  * number a "lower ID" in this class.)
  *
  * It's small enough to send over the wire. A 10M-document docset will have a
  * 10M-element bitset, which is 1.25MB.
  */
case class DocumentIdSet(
  /** DocumentSet ID */
  val documentSetId: Int,

  /** Set of last 32-bit components of all matching Document IDs. */
  val lowerIds: immutable.BitSet
) extends DocumentIdFilter {
  override def contains(documentId: Long): Boolean = {
    val inDocumentSet = (documentId >> 32).toInt == documentSetId 
    val inLowerIds = lowerIds.contains(documentId.toInt)
    inDocumentSet && inLowerIds
  }

  /** Document IDs, in an Array.
    *
    * The return value will be sorted by ID.
    */
  def toArray: Array[Long] = {
    val upperBits: Long = documentSetId.toLong << 32
    val ret = new Array[Long](size)
    lowerIds.zipWithIndex.foreach { case (lowerIds, index) => ret(index) = upperBits | lowerIds }
    ret
  }

  /** Document IDs, in a Seq.
    *
    * The return value will be sorted by ID.
    */
  def toSeq: Seq[Long] = toArray.toSeq

  override def intersect(that: DocumentIdFilter) = that match {
    case DocumentIdFilter.All(thatDocumentSetId) if documentSetId == thatDocumentSetId => this
    case DocumentIdSet(thatDocumentSetId, thatLowerIds) if documentSetId == thatDocumentSetId => {
      DocumentIdSet(documentSetId, lowerIds.intersect(thatLowerIds))
    }
    case _ => DocumentIdFilter.Empty
  }

  /** Number of documents. */
  lazy val size: Int = lowerIds.size
}

object DocumentIdSet {
  def apply(ids: immutable.Seq[Long]): DocumentIdSet = {
    ids.headOption match {
      case None => DocumentIdSet.empty
      case Some(id) => {
        val documentSetId = (id >> 32).toInt
        val bitSet = immutable.BitSet(ids.map(_.toInt): _*)
        DocumentIdSet(documentSetId, bitSet)
      }
    }
  }

  def empty: DocumentIdSet = DocumentIdSet(0, immutable.BitSet.empty)
}
