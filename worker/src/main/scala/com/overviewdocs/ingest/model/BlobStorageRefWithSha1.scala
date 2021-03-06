package com.overviewdocs.ingest.model

import com.overviewdocs.models.BlobStorageRef

case class BlobStorageRefWithSha1(
  ref: BlobStorageRef,
  sha1: Array[Byte]
) {
  def location = ref.location
  def nBytes = ref.nBytes
}
