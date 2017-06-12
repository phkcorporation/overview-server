package models

import com.overviewdocs.searchindex.SearchWarning

/** Something about a Selection's results the user should know. */
sealed trait SelectionWarning
object SelectionWarning {
  /** The search index did not return an accurate set of documents. */
  case class SearchIndexWarning(warning: SearchWarning) extends SelectionWarning
}