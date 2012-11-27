/*
 * DocumentWriterSpec.scala
 * 
 * Overview Project
 * Created by Jonas Karlsson, Aug 2012
 */

package persistence

import anorm._
import anorm.SqlParser._
import testutil.DbSpecification
import org.specs2.mutable.Specification
import testutil.DbSetup._

class DocumentWriterSpec extends DbSpecification {

  step(setupDb)

  "DocumentWriter" should {

    "update description of document" in new DbTestContext {
      val documentSetId = insertDocumentSet("DocumentWriterSpec")

      val writer = new DocumentWriter(documentSetId)
      val title = "title"
      val documentCloudId = "documentCloud-id"
      val description = "some,terms,together"
      
      val id = insertDocument(documentSetId, title, documentCloudId)
      writer.updateDescription(id, description)

      val documents =
        SQL("SELECT id, title, documentcloud_id FROM document").
          as(long("id") ~ str("title") ~ str("documentcloud_id") map (flatten) *)

      documents must haveTheSameElementsAs(Seq((id, description, documentCloudId)))
      
    }
  }

  step(shutdownDb)
}
