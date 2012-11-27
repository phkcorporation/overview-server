/*
 * NodeWriterSpec.scala
 * 
 * Overview Project
 * Created by Jonas Karlsson, Aug 2012
 */

package persistence

import anorm._
import anorm.SqlParser._

import testutil.DbSpecification
import java.sql.Connection
import overview.clustering.DocTreeNode
import overview.clustering.ClusterTypes.DocumentID
import org.specs2.mutable.Specification
import scala.collection.mutable.Set
import testutil.DbSetup._



class NodeWriterSpec extends DbSpecification {
  
  step(setupDb)


  private def addChildren(parent: DocTreeNode, description: String) : Seq[DocTreeNode] = {
    val children = for (i <- 1 to 2) yield new DocTreeNode(Set())
    children.foreach(_.description = description)
    children.foreach(parent.children.add)
    
    children
  }
  
  private val nodeDataParser = long("id") ~ str("description") ~ 
   get[Option[Long]]("parent_id") ~ long("document_set_id")
		  					   
  "NodeWriter" should {
    
    "insert root node with description, document set, and no parent" in new DbTestContext {
      val documentSetId = insertDocumentSet("NodeWriterSpec")          
      val root = new DocTreeNode(Set())
      val description = "description"
      root.description = description
      
      val writer = new NodeWriter(documentSetId)
      
      writer.write(root)
      
      val result = 
        SQL("SELECT id, description, parent_id, document_set_id FROM node").
      as(nodeDataParser map(flatten) singleOpt)
                      
      result must beSome
      val (id, rootDescription, parentId, rootDocumentSetId) = result.get
      
      rootDescription must be equalTo(description)
      parentId must beNone
      rootDocumentSetId must be equalTo(documentSetId)
    }
    
    "insert child nodes" in new DbTestContext {
      val documentSetId = insertDocumentSet("NodeWriterSpec")
      val root = new DocTreeNode(Set())
      root.description = "root"
      val childNodes = addChildren(root, "child")
      val grandChildNodes = childNodes.map(n => (n, addChildren(n, "grandchild")))
      val writer = new NodeWriter(documentSetId)
      
      writer.write(root)
      
      val savedRoot = SQL("""
                          SELECT id, description, parent_id, document_set_id FROM node
                          WHERE description = 'root'
                          """).as(nodeDataParser map(flatten) singleOpt)
        
      savedRoot must beSome
      val (rootId, _, _, _) = savedRoot.get
      
      val savedChildren = 
        SQL("""
    	    SELECT id, description, parent_id, document_set_id FROM node
            WHERE parent_id = {rootId} AND description = 'child'
    		""").on("rootId" -> rootId).as(nodeDataParser map(flatten) *)
 
      val childIds = savedChildren.map(_._1)
      childIds must have size(2)
      
      val savedGrandChildren =
        SQL("""
    	    SELECT id, description, parent_id, document_set_id FROM node
            WHERE parent_id IN """ + childIds.mkString("(", ",", ")") + """ 
            AND description = 'grandchild'
    		""").on("rootId" -> rootId).as(nodeDataParser map(flatten) *)
      
      savedGrandChildren must have size(4)
    }
    
    "insert document into node_document table" in new DbTestContext {
      val documentSetId = insertDocumentSet("NodeWriterSpec")
      val documentIds = for (i <- 1 to 5) yield 
        insertDocument(documentSetId, "title", "documentCloudId")
      val idSet = Set(documentIds: _*)
      
      val node = new DocTreeNode(idSet)
      node.description = "node"
      val writer = new NodeWriter(documentSetId)
      
      writer.write(node)
      
      val savedNode = SQL("SELECT id FROM node WHERE description = 'node'").
                     as(long("id") singleOpt)
                     
      savedNode must beSome
      val nodeId = savedNode.get
      
      val nodeDocuments = 
        SQL("""
            SELECT node_id, document_id FROM node_document
            """).as(long("node_id") ~ long("document_id") map(flatten) *)
      
      val expectedNodeDocuments = documentIds.map((nodeId, _))
      
      nodeDocuments must haveTheSameElementsAs(expectedNodeDocuments)
    } 
  }
  
  step(shutdownDb)
}
