package com.overviewdocs.ingest.process

import akka.stream.scaladsl.Source
import akka.util.ByteString
import play.api.libs.json.JsObject

/** Part of the output of a Step: something we should write somewhere.
  *
  * Step data is sent over the wire: data is serialized. So the order in
  * which we process data is important. This trait class describes that order.
  */
sealed trait StepOutputFragment

object StepOutputFragment {
  /** Messages that lead us to write information about a child File2.
    *
    * indexInParent is explicit: if the producer restarts or a fragment is
    * doubled (we have at-least-once delivery over the network), we'll want to
    * ignore fragments we've already seen.
    */
  trait ChildFragment extends StepOutputFragment {
    val indexInParent: Int
  }

  /** Fragment that marks the end of the stream.
    *
    * If another fragment comes afterwards, it is an error. If a stream ends
    * without an EndFragment, it is an error.
    */
  sealed trait EndFragment extends StepOutputFragment

  /** Heartbeat: tells us (and the end-user) the pipeline is alive. */
  sealed trait Progress extends StepOutputFragment {
    /** `tElapsed / tTotal`, where `tTotal` is the predicted amount of time the
      * job will take.
      */
    def fraction: Double
  }
  trait RationalProgress extends Progress {
    val nProcessed: Int
    val nTotal: Int
    override def fraction = 1.0 * nProcessed / nTotal
  }

  /** nProcessed/nTotal bytes of input have been processed.
    *
    * This is preferred over `RationalProgress` because it can give the user a
    * better understanding of how much work remains.
    */
  case class ProgressBytes(
    override val nProcessed: Int,
    override val nTotal: Int
  ) extends RationalProgress

  /** nProcessed/nTotal File2s of output have been produced.
    *
    * This is preferred over `RationalProgress` because it can give the user a
    * better understanding of how much work remains.
    */
  case class ProgressChildren(
    override val nProcessed: Int,
    override val nTotal: Int
  ) extends RationalProgress

  /** fraction has elapsed of the total predicted processing time for the input
    * File2.
    */
  case class ProgressFraction(
    override val fraction: Double
  ) extends Progress

  /** Metadata about a new File2 we should write.
    *
    * This can be used to create a CREATED File2 in the database. The next
    * fragment(s) should stream its blob contents.
    *
    * When a second File2Header fragment arrives, the receiver can assume the
    * previous File2 is completely transferred. The previous File2 should
    * transition to WRITTEN, meaning we can leave it alone if we restart the
    * pipeline with the same input File2 (during a deploy, say, or after a
    * crash).
    */
  case class File2Header(
    indexInParent: Int,
    filename: String,
    contentType: String,
    languageCode: String,
    metadata: JsObject,
    wantOcr: Boolean,
    wantSplitByPage: Boolean
  ) extends StepOutputFragment

  /** Blob data for the current File2.
    *
    * indexInParent is explicit: if the producer restarts or a fragment is
    * doubled (we have at-least-once delivery over the network), we'll want to
    * ignore fragments for children we've already handled.
    */
  case class Blob(
    override val indexInParent: Int,
    rawBytes: Source[ByteString, _]
  ) extends ChildFragment

  /** Indicates there will be no Blob data for the current File2.
    *
    * This is for an edge case. Imagine a "OCR" pipeline element that outputs a
    * PDF the same as the input PDF, except with OCR applied. There's no need
    * to pass the blob over the pipeline, because the pipeline already has it.
    *
    * This message only makes sense for child `0`.
    */
  case object InheritBlob extends ChildFragment {
    override val indexInParent: Int = 0
  }

  /** Text data for the current File2.
    *
    * Text data is optional for all but a leaf File2.
    *
    * indexInParent is explicit: if the producer restarts or a fragment is
    * doubled (we have at-least-once delivery over the network), we'll want to
    * ignore fragments for children we've already handled.
    */
  case class Text(
    override val indexInParent: Int,
    utf8Bytes: Source[ByteString, _]
  ) extends ChildFragment

  /** Thumbnail data for the current File2.
    *
    * Thumbnail data is optional for all but a leaf File2.
    *
    * indexInParent is explicit: if the producer restarts or a fragment is
    * doubled (we have at-least-once delivery over the network), we'll want to
    * ignore fragments for children we've already handled.
    */
  case class Thumbnail(
    override val indexInParent: Int,
    contentType: String,
    bytes: Source[ByteString, _]
  ) extends ChildFragment

  /** All child File2s have been transmitted, and the conversion succeeded.
    */
  case object Done extends EndFragment

  /** The user canceled the Step2 before it finished producing child File2s.
    *
    * The last-transmitted File2 should not be considered `WRITTEN`, even if a
    * lot of fragments arrived for it, because we never marked it as `WRITTEN`.
    */
  case object Canceled extends EndFragment

  /** A StepStep determined the input File2 was invalid.
    *
    * In other words: a normal error.
    *
    * The Step's invoker can decide what to do with the data that has
    * already been transmitted. The end user may want it: for instance, if the
    * input is a truncated CSV or Zip it would be nice to show the user all the
    * valid documents that were produced before the error.
    */
  case class FileError(message: String) extends EndFragment

  /** A StepLogic crashed, and an email was sent to Overview developers.
    *
    * In other words: a bug in Overview.
    *
    * The Step's invoker can decide what to do with the data that has
    * already been transmitted. The end user may want it: for instance, if the
    * input is a truncated CSV or Zip it would be nice to show the user all the
    * valid documents that were produced before the error.
    *
    * A note about graceful deploys: if a StepLogic relies upon external
    * daemons, the StepLogic and daemons will arrange themselves such that
    * a daemon restart or scale-down does not cause a StepError.
    */
  case class StepError(exception: Exception) extends EndFragment
}
