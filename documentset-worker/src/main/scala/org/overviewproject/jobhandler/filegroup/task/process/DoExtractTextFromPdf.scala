package org.overviewproject.jobhandler.filegroup.task.process

import org.overviewproject.models.File
import org.overviewproject.jobhandler.filegroup.task.step.PdfFileDocumentData
import org.overviewproject.jobhandler.filegroup.task.step.TaskStep
import org.overviewproject.jobhandler.filegroup.task.step.ExtractTextFromPdf

class DoExtractTextFromPdf(documentSetId: Long) extends StepGenerator[File, Seq[PdfFileDocumentData]] {
  override def generate(f: File): TaskStep = {
    ExtractTextFromPdf(documentSetId, f, nextStepGenerator.get.generate _)
  }
}