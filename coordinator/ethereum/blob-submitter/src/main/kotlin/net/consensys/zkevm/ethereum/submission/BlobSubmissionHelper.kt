package net.consensys.zkevm.ethereum.submission

import net.consensys.zkevm.domain.BlobRecord
import net.consensys.zkevm.domain.BlockInterval
import net.consensys.zkevm.domain.BlockIntervals
import net.consensys.zkevm.domain.assertConsecutiveIntervals
import net.consensys.zkevm.domain.toBlockIntervalsString
import org.apache.logging.log4j.Logger
import kotlin.math.min

/**
 * Chunks blobs into lists of blobs, each list representing a chunk of blobs that can be submitted
 * Example:
 * blobs = [0..9, 10..19, 20..29, 30..39, 40..49, 50..59, 60..69, 70..79, 80..89, 90..99, 100..109, 110..119, 120..121]
 * aggregations = [0..69, 70..89]
 * targetChunkSize = 3
 * Result: [
 *   [0..9, 10..19, 20..29],
 *   [30..39, 40..49, 50..59,],
 *   [60..69],
 *   [70..79, 80..89]
 * ]
 *
 * Note: it's assumed that blobs and aggregations are sorted by startBlockNumber and sequential without gaps.
 * This is a system-wide invariant that must be enforced by conflation/aggregation logic.
 */
fun <T : BlockInterval> chunkBlobs(
  blobsIntervals: List<T>,
  aggregations: BlockIntervals,
  targetChunkSize: Int
): List<List<T>> {
  require(targetChunkSize > 0) { "targetChunkSize must be greater than 0" }
  assertConsecutiveIntervals(blobsIntervals)
  require(blobsIntervals.isNotEmpty()) { "blobsIntervals must not be empty" }
  val aggregationsIntervals = aggregations.toIntervalList()
  // 1st blob.startBlockNumber does not need to be equal to 1st aggregation.startBlockNumber
  // because we may have already submitted some blobs of 1st aggregation in previous ticks.
  require(blobsIntervals.first().startBlockNumber >= aggregations.startingBlockNumber) {
    inconsistentBlobAggregationMessage(blobsIntervals, aggregationsIntervals)
  }
  val chunks = mutableListOf<List<T>>()
  var aggregationIndex = 0
  var blobsIntervalsIndex = 0
  while (aggregationIndex < aggregationsIntervals.size && blobsIntervalsIndex < blobsIntervals.size) {
    val aggregation = aggregationsIntervals[aggregationIndex]
    val chunk = blobsIntervals
      .subList(blobsIntervalsIndex, min(blobsIntervalsIndex + targetChunkSize, blobsIntervals.size))
      .takeWhile { blobInterval -> blobInterval.endBlockNumber <= aggregation.endBlockNumber }
      .also { chunk ->
        // just a sanity check for invariant that blobs must match aggregations intervals
        require(chunk.isNotEmpty()) {
          inconsistentBlobAggregationMessage(blobsIntervals, aggregationsIntervals)
        }
      }
    blobsIntervalsIndex += chunk.size
    if (chunk.last().endBlockNumber == aggregation.endBlockNumber) {
      aggregationIndex++
    } else if (chunk.size < targetChunkSize && blobsIntervalsIndex == blobsIntervals.size) break
    chunks.add(chunk)
  }
  return chunks
}

private fun inconsistentBlobAggregationMessage(
  blobsIntervals: List<BlockInterval>,
  aggregationsIntervals: List<BlockInterval>
): String {
  return "blobs=${blobsIntervals.toBlockIntervalsString()} are inconsistent with " +
    "aggregations=${aggregationsIntervals.toBlockIntervalsString()}"
}

internal fun filterOutRecordsThatDoNotFollowSameSubmissionMethod(
  blobRecords: List<BlobRecord>
): List<BlobRecord> {
  return if (blobRecords.isEmpty()) {
    blobRecords
  } else {
    val eip4844Enabled = blobRecords.first().blobCompressionProof!!.eip4844Enabled
    blobRecords.takeWhile { it.blobCompressionProof!!.eip4844Enabled == eip4844Enabled }
  }
}

internal fun logSubmissionError(
  log: Logger,
  intervalString: String,
  error: Throwable,
  isEthCall: Boolean = false
) {
  val logLevel = getErrorAppropriateLevel(error, isEthCall)
  logSubmissionError(
    log,
    "{} for blob submission failed: blob={} errorMessage={}",
    intervalString,
    error,
    isEthCall,
    logLevel
  )
}
