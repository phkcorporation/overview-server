package org.overviewproject.test

import anorm._
import java.sql.Connection

object DbSetup {

  private def failInsert = { throw new Exception("failed insert") }

  def insertCsvImportDocumentSet(uploadedFileId: Long)(implicit c: Connection): Long = {
    SQL("""
      INSERT INTO document_set (type, public, title, uploaded_file_id, created_at, document_count, import_overflow_count)
      VALUES ('CsvImportDocumentSet', 'false', {title}, {uploadedFileId}, TIMESTAMP '1970-01-01 00:00:00', 100, 0)
      """).on(
      'title -> "Csv Import",
      'uploadedFileId -> uploadedFileId).executeInsert().getOrElse(failInsert)
  }

  def insertDocumentSet(query: String)(implicit c: Connection): Long = {
    SQL("""
      INSERT INTO document_set (type, public, title, query, created_at, document_count, import_overflow_count)
      VALUES ('DocumentCloudDocumentSet'::document_set_type, 'false', {title}, {query}, TIMESTAMP '1970-01-01 00:00:00', 100, 0)
      """).on(
      'title -> ("From query: " + query),
      'query -> query).executeInsert().getOrElse(failInsert)
  }
  
  def insertUploadedFile(contentDisposition: String, contentType: String, size: Long)(implicit c: Connection): Long = {
    SQL("""
        INSERT INTO uploaded_file (content_disposition, content_type, uploaded_at, size)
        VALUES ({contentDisposition}, {contentType}, TIMESTAMP '1970-01-01 00:00:00', {size})
        """).on(
            "contentDisposition" -> contentDisposition,
            "contentType" -> contentType,
            "size" -> size).executeInsert().getOrElse(failInsert)
  }

  def insertNode(documentSetId: Long, parentId: Option[Long], description: String)(implicit c: Connection): Long = {
    SQL("""
      INSERT INTO node (document_set_id, parent_id, description)
      VALUES ({document_set_id}, {parent_id}, {description})
      """).on(
      'document_set_id -> documentSetId,
      'parent_id -> parentId,
      'description -> description).executeInsert().getOrElse(failInsert)
  }

  def insertDocument(documentSetId: Long, description: String, documentCloudId: String, title: Option[String] = None)(implicit connection: Connection): Long = {
    SQL("""
        INSERT INTO document (type, document_set_id, description, documentcloud_id, title)
        VALUES ('DocumentCloudDocument'::document_type, {documentSetId}, {description}, {documentCloudId}, {title})
        """).on("documentSetId" -> documentSetId,
      "description" -> description, "documentCloudId" -> documentCloudId, "title" -> title).
      executeInsert().getOrElse(failInsert)
  }

  def insertNodeDocument(nodeId: Long, documentId: Long)(implicit c: Connection): Long = {
    SQL("""
        INSERT INTO node_document(node_id, document_id) VALUES ({nodeId}, {documentId})
        """).on("nodeId" -> nodeId, "documentId" -> documentId).
      executeInsert().getOrElse(failInsert)
  }

  def insertTag(documentSetId: Long, name: String)(implicit c: Connection): Long = {
    SQL("""
        INSERT INTO tag (name, document_set_id)
        VALUES ({name}, {documentSetId})
        """).on("name" -> name, "documentSetId" -> documentSetId).
      executeInsert().getOrElse(failInsert)
  }

  def insertDocumentWithNode(documentSetId: Long,
    description: String, documentCloudId: String, nodeId: Long)(implicit connection: Connection): Long = {
    val documentId = insertDocument(documentSetId, description, documentCloudId)
    insertNodeDocument(nodeId, documentId)

    documentId
  }

  def insertNodes(documentSetId: Long, count: Int)(implicit c: Connection): Seq[Long] = {
    for (i <- 1 to count) yield insertNode(documentSetId, None, "node-" + i)
  }

  def insertDocumentsForeachNode(documentSetId: Long, nodeIds: Seq[Long],
    documentCount: Int)(implicit c: Connection): Seq[Long] = {

    nodeIds.flatMap(n =>
      for (i <- 1 to documentCount) yield insertDocumentWithNode(documentSetId,
        "description-" + i, "documentcloudId-" + i, n))
  }

  def insertDocuments(documentSetId: Long, count: Int)(implicit c: Connection): Seq[Long] = {
    for (i <- 1 to count) yield insertDocument(documentSetId, "description-" + i, "documentCloudId-" + i)
  }

  def tagDocuments(tagId: Long, documentIds: Seq[Long])(implicit c: Connection): Long = {
    SQL("""
        INSERT INTO document_tag (document_id, tag_id)
        SELECT id, {tagId} FROM document
        WHERE id IN """ + documentIds.mkString("(", ",", ")")).on("tagId" -> tagId).executeUpdate()
  }
}
