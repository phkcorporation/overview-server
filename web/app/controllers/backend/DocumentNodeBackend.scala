package controllers.backend

import com.google.inject.ImplementedBy
import javax.inject.Inject
import scala.collection.immutable
import scala.concurrent.Future

import com.overviewdocs.database.Database
import models.Selection

@ImplementedBy(classOf[DbDocumentNodeBackend])
trait DocumentNodeBackend extends Backend {
  /** Gives a list of Node IDs for each Document.
    *
    * There are no empty lists: they are not defined.
    *
    * The returned lists are not ordered.
    */
  def indexMany(documentIds: immutable.Seq[Long]): Future[Map[Long,immutable.Seq[Long]]]

  /** Counts how many NodeDocuments exist, counted by Node.
    *
    * There are no zero counts: they are not defined.
    *
    * Security implications: a user can query across multiple trees. The user
    * is restricted to the current document set by <tt>selection</tt>.
    *
    * @param selection The documents we care about
    * @param nodeIds The nodes we care about
    */
  def countByNode(selection: Selection, nodeIds: immutable.Seq[Long]): Future[Map[Long,Int]]
}

class DbDocumentNodeBackend @Inject() (
  val database: Database
) extends DocumentNodeBackend with DbBackend {
  import database.api._
  import database.executionContext

  private def countByDocumentsAndNodes(documentIds: immutable.Seq[Long], nodeIds: immutable.Seq[Long]): Future[Map[Long,Int]] = {
    if (documentIds.isEmpty) {
      Future.successful(Map())
    } else if (nodeIds.isEmpty) {
      Future.successful(Map())
    } else {
      // Slick is slow at compiling queries. We need this query _fast_
      database.run(sql"""
        SELECT node_id, COUNT(*)
        FROM node_document
        WHERE node_id IN (#${nodeIds.mkString(",")})
          AND document_id IN (#${documentIds.mkString(",")})
        GROUP BY node_id
      """.as[(Long,Int)])
        .map(_.toMap)
    }
  }

  override def indexMany(documentIds: immutable.Seq[Long]) = {
    if (documentIds.isEmpty) {
      Future.successful(Map())
    } else {
      import slick.jdbc.GetResult
      implicit val rconv = GetResult(r => (r.nextLong() -> r.nextArray[Long]().toVector))
      database.run(sql"""
        SELECT document_id, ARRAY_AGG(node_id)
        FROM node_document
        WHERE document_id IN (#${documentIds.mkString(",")})
        GROUP BY document_id
      """.as[(Long,immutable.Seq[Long])])
        .map(_.toMap)
    }
  }

  override def countByNode(selection: Selection, nodeIds: immutable.Seq[Long]) = {
    for {
      documentIds <- selection.getAllDocumentIds
      result <- countByDocumentsAndNodes(documentIds, nodeIds)
    } yield result
  }
}
