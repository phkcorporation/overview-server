/**
 * GenerateClusters.scala
 * Given a set of document vectors, return a tree hierarchical tree of clusters
 * Based on an algorithm by Stephen Ingram
 *
 * Overview Project, created July 2012
 *
 * @author Jonathan Stray
 *
 */

package overview.clustering

import scala.collection.mutable.{ Set, Stack, PriorityQueue, Map }
import ClusterTypes._
import overview.util.Logger
import overview.util.Progress._
import overview.util.DocumentSetCreationJobStateDescription
import overview.util.DocumentSetCreationJobStateDescription._
import overview.util.CompactPairArray
import scala.collection.mutable.AddingBuilder

object ConnectedComponents {

  // Takes a node, and a set of unvisited nodes, and yields all nodes we an visit next
  type EdgeEnumerationFn[T] = (T, Set[T]) => Iterable[T]

  // Returns component containing startNode, plus all nodes not in component
  def SingleComponent[T](startNode: T, allNodes: Set[T], edgeEnumerator: EdgeEnumerationFn[T]): (Set[T], Set[T]) = {
    var component = Set[T](startNode) // all nodes found to be in the component so far
    val frontier = Stack[T](startNode) // nodes in the component that we have not checked the edges of
    var remaining = allNodes - startNode // nodes not yet visited

    // walk outward from each node in the frontier, until the frontier is empty or we run out of nodes
    while (!frontier.isEmpty && !remaining.isEmpty) {
      val a = frontier.pop

      for (b <- edgeEnumerator(a, remaining)) { // for every remaining we can reach from a...
        component += b
        frontier.push(b)
        remaining -= b
      }
    }

    (component, remaining)
  }

  // Produce all connected components
  def AllComponents[T](allNodes: Set[T], edgeEnumerator: EdgeEnumerationFn[T]): Set[Set[T]] = {
    var components = Set[Set[T]]()
    var remaining = allNodes

    while (!remaining.isEmpty) {
      val (newComponent, leftOvers) = SingleComponent(remaining.head, remaining, edgeEnumerator)
      components += newComponent
      remaining = leftOvers
    }

    components
  }
}

// Encapsulates document-document distance function. Returns in range 0 == identical to 1 == unrelated
object DistanceFn {
  
  // sparse dot product on two term->float maps
  // Not written in very functional style, as DocumentVector uses an awkward representation as arrays for space reasons
  // Basic intersection of sorted lists algorithm
  private def SparseDot(a: DocumentVector, b: DocumentVector): Double = {
    var a_idx = 0
    var b_idx = 0
    var dot = 0.0
    
    while (a_idx < a.length && b_idx < b.length) {
      val a_term = a.terms(a_idx)
      val b_term = b.terms(b_idx)
      
      if (a_term < b_term) {
        a_idx += 1
      } else if (b_term < a_term) {
        b_idx += 1
      } else {
        dot += a.weights(a_idx).toDouble * b.weights(b_idx).toDouble
        a_idx += 1
        b_idx += 1
      } 
    }
  
    dot
  }

  // Document distance computation. Returns 1 - similarity, where similarity is cosine of normalized vectors
  def CosineDistance(a: DocumentVector, b: DocumentVector) = {
    1.0 - SparseDot(a, b)
  }
}

// Document tree node. Contains items, children, and a description which lists top terms in all docs of this node
class DocTreeNode(val docs: Set[DocumentID]) {
  var description = ""
  var children: Set[DocTreeNode] = Set[DocTreeNode]()

  // return children in predictable order. Sort descending by size, then ascending by document IDs
  def OrderedChildren: List[DocTreeNode] = {
    children.toList.sortWith((a, b) => (a.docs.size > b.docs.size) || (a.docs.size == b.docs.size && a.docs.min < b.docs.min))
  }

  // simple string representation, good for unit tests. We sort the sets to ensure consistent output
  override def toString = {
    "(" + docs.toList.sorted.mkString(",") +
      (if (!children.isEmpty)
        ", " + OrderedChildren.mkString(", ")
      else
        "") +
      ")"
  }

  //  Tree pretty printer
  def prettyString(indent: Int = 0): String = {
    " " * indent + docs.toList.sorted.mkString(",") +
      " -- " + description +
      (if (!children.isEmpty)
        "\n" + OrderedChildren.map(_.prettyString(indent + 4)).mkString("\n")
      else
        "")
  }
}

class DocTreeBuilder(val docVecs: DocumentSetVectors, val distanceFn:DocumentDistanceFn) {

  var sampledEdges = new SampledEdges
  
  // Produces all docs reachable from a given start doc, given thresh
  // Unoptimized implementation, scans through all possible edges (N^2 total)
  private def allReachableDocs(thresh: Double, thisDoc: DocumentID, otherDocs: Set[DocumentID]): Iterable[DocumentID] = {
    for (otherDoc <- otherDocs; 
        if distanceFn(docVecs(thisDoc), docVecs(otherDoc)) <= thresh)
      yield otherDoc
  }
  
  // Same logic as above, but only looks through edges stored in sampledEdges
  private def sampledReachableDocs(thresh: Double, thisDoc: DocumentID, otherDocs: Set[DocumentID]): Iterable[DocumentID] = {
    val g = sampledEdges.get(thisDoc)
    if (g.isDefined) {
      val a = g.get.toArray
      for ((otherDoc, distance) <- a
           if otherDocs.contains(otherDoc);
           if distance <= thresh)
        yield otherDoc
    } else {
      Nil
    }
  }
  
  // Returns an edge walking function suitable for ConnectedComponents, using the sampled edge set if we have it
  private def createEdgeEnumerator(thresh:Double) = {
    if (!sampledEdges.isEmpty)
      (doc:DocumentID,docSet:Set[DocumentID]) => sampledReachableDocs(thresh, doc, docSet)
    else
      (doc:DocumentID,docSet:Set[DocumentID]) => allReachableDocs(thresh, doc, docSet)
  }
  
  // Expand out the nodes of the tree by thresholding the documents in each and seeing if they split into components
  // Returns new set of leaf nodes
  private def ExpandTree(currentLeaves: List[DocTreeNode], thresh: Double) = {
    var nextLeaves = List[DocTreeNode]()

    for (node <- currentLeaves) {

      val childComponents = ConnectedComponents.AllComponents[DocumentID](node.docs, createEdgeEnumerator(thresh))

      if (childComponents.size == 1) {
        // lower threshold did not split this component, pass unchanged to next level
        nextLeaves = node :: nextLeaves
      } else {
        // lower threshold did split this component, create a child TreeNode for each resulting component
        for (component <- childComponents) {
          val newLeaf = new DocTreeNode(component)
          node.children += newLeaf
          nextLeaves = newLeaf :: nextLeaves
        }
      }
    }

    nextLeaves
  }

  // Steps distance thresh along given sequence. First step must always be 1 = full graph, 0 must always be last = leaves
  def BuildTree(threshSteps: Seq[Double],
    progAbort: ProgressAbortFn = NoProgressReporting): DocTreeNode = {
    require(threshSteps.head == 1.0)
    require(threshSteps.last == 0.0)
    require(threshSteps.forall(step => step >= 0 && step <= 1.0))
    val numSteps: Double = threshSteps.size

    // root thresh=1.0 is one node with all documents
    progAbort(Progress(0, ClusteringLevel(1)))
    var topLevel = Set(docVecs.keys.toArray: _*)
    val root = new DocTreeNode(topLevel)

    // intermediate levels created by successively thresholding all edges, (possibly) breaking each component apart
    var currentLeaves = List(root)
    for (curStep <- 1 to threshSteps.size - 2) {
      progAbort(Progress(curStep / numSteps, ClusteringLevel(curStep + 1)))
      currentLeaves = ExpandTree(currentLeaves, threshSteps(curStep))
    }

    // bottom level thresh=0.0 is one leaf node for each document
    progAbort(Progress((numSteps - 1) / numSteps, ClusteringLevel(numSteps.toInt)))
    for (node <- currentLeaves) {
      if (node.docs.size > 1) // don't expand if already one node
        node.children = node.docs.map(item => new DocTreeNode(Set(item)))
    }

    root
  }


  // Turn a set of document vectors into a descriptive string. Takes top weighted terms, separates by commas
  def makeDescription(vec: DocumentVectorMap): String = {
    val maxTerms = 15
    vec.toList.sortWith(_._2 > _._2).take(maxTerms).map(_._1).map(docVecs.stringTable.idToString(_)).mkString(", ")
  }

  // Sparse vector sum, used for computing node descriptions
  def accumDocumentVector(acc: DocumentVectorMap, v: DocumentVectorMap): Unit = {
    v foreach {
      case (id, weight) => acc.update(id, acc.getOrElse(id, 0f) + weight)
    }
  }

  // Create a descriptive string for each node, by taking the sum of all document vectors in that node.
  // Building all descriptions at once allows a lot of re-use of sub-sums.
  def labelNode(node: DocTreeNode): DocumentVectorMap = {
    
    if (node.docs.size == 1) {
      require(node.children.isEmpty)
      val vec = DocumentVectorMap(docVecs(node.docs.head)) // get document vector corresponding to our single document ID
      node.description = makeDescription(vec)
      vec
    } else {
      var vec = DocumentVectorMap()
      for (child <- node.children) {
        accumDocumentVector(vec, labelNode(child)) // sum the document vectors of all child nodes
      }
      node.description = makeDescription(vec)
      vec
    }
  }
  
  def sampleCloseEdges() : Unit = {
    sampledEdges = new EdgeSampler(docVecs, distanceFn).edges
  }

}

// Given a set of document vectors, generate a tree of nodes and their descriptions
object BuildDocTree {

  val numDocsWhereSamplingHelpful = 1000 
  
  def apply(docVecs: DocumentSetVectors, progAbort: ProgressAbortFn = NoProgressReporting): DocTreeNode = {
    // By default: cosine distance, and step down in 0.1 increments
    val distanceFn = DistanceFn.CosineDistance _
    val threshSteps = List(1, 0.9, 0.8, 0.7, 0.6, 0.5, 0.4, 0.3, 0.2, 0.1, 0) // can't do (1.0 to 0.1 by -0.1) cause last val must be exactly 0

    val builder = new DocTreeBuilder(docVecs, distanceFn)
    if (docVecs.size > numDocsWhereSamplingHelpful)             
      builder.sampleCloseEdges()                                // use sampled edges if the docset is large
    val tree = builder.BuildTree(threshSteps, progAbort)        // actually build the tree!
    builder.labelNode(tree)                                     // create a descriptive label for each node

    tree
  }

}
