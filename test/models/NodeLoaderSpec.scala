package models

import play.api.test.FakeApplication
import play.api.Play.{ start, stop }
import org.overviewproject.test.DbSetup._
import org.overviewproject.test.Specification
import org.overviewproject.tree.orm.{ Node => OrmNode }
import helpers.DbTestContext
import models.core.Node
import models.core.DocumentIdList

class NodeLoaderSpec extends Specification {
  step(start(FakeApplication()))

  "NodeLoader" should {

    trait NodeContext extends DbTestContext {
      var documentSetId: Long = _
      var root: Node = _

      val nodeLoader = new NodeLoader

      override def setupWithDb = {
        documentSetId = insertDocumentSet("SubTreeDataLoaderSpec")
        val r = createNode(None, "root")
        root = Node(r.id, r.description, Seq.empty, DocumentIdList(Seq.empty, 0), Map())
      }

      protected def createNode(parentId: Option[Long], description: String, size: Int = 0): OrmNode =
        OrmNode(documentSetId, parentId, description, size, Array[Long]()).save
    }

    trait SmallTreeContext extends NodeContext {
      var nodeIds: Seq[Long] = _

      override def setupWithDb = {
        super.setupWithDb
        nodeIds = root.id +: createNextLevel(root.id)
      }

      protected def createNextLevel(nodeId: Long): Seq[Long] = {
        for (i <- 1 to 2) yield {
          val c = createNode(Some(nodeId), "child")
          c.id
        }
      }

    }

    trait LargerTreeContext extends SmallTreeContext {
      override def setupWithDb = {
        super.setupWithDb

        val level2 = nodeIds.tail.flatMap(createNextLevel)
        val level3 = level2.flatMap(createNextLevel)
        nodeIds ++= level2 ++ level3
      }
    }

    trait ChildNodesToBeOrdered extends NodeContext {
      var idsSortedBySizeAndId: Seq[Long] = _

      override def setupWithDb = {
        super.setupWithDb
        val nodeSizes = Seq(1000, 2000, 2000, 3000, 2000, 5000)
        val children = nodeSizes.map(s => createNode(Some(root.id), "child", s))
        val childIds = children.map(_.id)
        idsSortedBySizeAndId =
          childIds.zip(nodeSizes).sortWith((a, b) =>
            (a._2 > b._2) || (a._2 == b._2 && a._1 < b._1)).map(_._1)
      }
    }

    "find the root node id" in new NodeContext {
      nodeLoader.loadRootId(documentSetId) must beSome.like { case id => id must be equalTo root.id }
    }

    "load a small tree" in new SmallTreeContext {
      val nodes = nodeLoader.loadTree(documentSetId, root.id, 1)
      nodes.map(_.id) must haveTheSameElementsAs(nodeIds)
    }

    "should handle too much depth" in new SmallTreeContext {
      val nodes = nodeLoader.loadTree(documentSetId, root.id, 10)
      nodes.map(_.id) must haveTheSameElementsAs(nodeIds)
    }

    "return root if depth is 0" in new SmallTreeContext {
      val nodes = nodeLoader.loadTree(documentSetId, root.id, 0)
      nodes.map(_.id) must contain(root.id).only
    }

    "handle multiple levels" in new LargerTreeContext {
      val nodes = nodeLoader.loadTree(documentSetId, root.id, 2)
      nodes.map(_.id) must haveTheSameElementsAs(nodeIds.take(7))
      nodes.map(_.childNodeIds).map { l => l must not beEmpty }
    }

    "return child ids sorted by node size and id" in new ChildNodesToBeOrdered {
      val nodes = nodeLoader.loadTree(documentSetId, root.id, 0)
      nodes(0).childNodeIds must be equalTo(idsSortedBySizeAndId)
    }
  }
  step(stop)
}