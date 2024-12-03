package linea.staterecover

import build.linea.domain.EthLogEvent
import build.linea.staterecover.BlockL1RecoveredData
import io.vertx.core.Vertx
import net.consensys.encodeHex
import net.consensys.linea.BlockNumberAndHash
import net.consensys.linea.BlockParameter
import net.consensys.zkevm.PeriodicPollingService
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import tech.pegasys.teku.infrastructure.async.SafeFuture
import kotlin.time.Duration

class StateSynchronizerService(
  private val vertx: Vertx,
  private val elClient: ExecutionLayerClient,
  private val submissionEventsClient: LineaRollupSubmissionEventsClient,
  private val blobsFetcher: BlobFetcher,
  private val transactionDetailsClient: TransactionDetailsClient,
  private val blobDecompressor: BlobDecompressorAndDeserializer,
  private val blockImporterAndStateVerifier: BlockImporterAndStateVerifier,
  private val pollingInterval: Duration,
  private val log: Logger = LogManager.getLogger(StateSynchronizerService::class.java)
) : PeriodicPollingService(
  vertx = vertx,
  log = log,
  pollingIntervalMs = pollingInterval.inWholeMilliseconds
) {
  private data class DataSubmittedEventAndBlobs(
    val ethLogEvent: EthLogEvent<DataSubmittedV3>,
    val blobs: List<ByteArray>
  )

  var lastProcessedFinalization: EthLogEvent<DataFinalizedV3>? = null
  private var stateRootMismatchFound: Boolean = false

  private fun findNextFinalization(): SafeFuture<EthLogEvent<DataFinalizedV3>?> {
    return if (lastProcessedFinalization != null) {
      submissionEventsClient
        .findDataFinalizedEventByStartBlockNumber(
          l2BlockNumber = lastProcessedFinalization!!.event.endBlockNumber + 1UL
        )
    } else {
      elClient.getBlockNumberAndHash(blockParameter = BlockParameter.Tag.LATEST)
        .thenCompose { headBlock ->
          // 1st, assuming head matches a prev finalization,
          val nextBlockToImport = headBlock.number + 1UL
          submissionEventsClient
            .findDataFinalizedEventByStartBlockNumber(l2BlockNumber = nextBlockToImport)
            .thenCompose { finalizationEvent ->
              if (finalizationEvent != null) {
                SafeFuture.completedFuture(finalizationEvent)
              } else {
                // 2nd: otherwise, local head may be in between, let's find corresponding finalization
                submissionEventsClient
                  .findDataFinalizedEventContainingBlock(l2BlockNumber = nextBlockToImport)
              }
            }
        }
    }
  }

  override fun action(): SafeFuture<Any?> {
    return findNextFinalization()
      .thenCompose { nextFinalization ->
        if (nextFinalization == null) {
          // nothing to do for now
          SafeFuture.completedFuture(null)
        } else {
          submissionEventsClient
            .findDataSubmittedV3EventsUntilNextFinalization(
              l2StartBlockNumberInclusive = nextFinalization.event.startBlockNumber
            )
        }
      }
      .thenCompose { submissionEvents ->
        if (submissionEvents == null) {
          SafeFuture.completedFuture("No new events")
        } else {
          getBlobsForEvents(submissionEvents.dataSubmittedEvents)
            .thenCompose { dataSubmissionsWithBlobs ->
              updateNodeWithBlobsAndVerifyState(dataSubmissionsWithBlobs, submissionEvents.dataFinalizedEvent.event)
            }
            .thenApply {
              lastProcessedFinalization = submissionEvents.dataFinalizedEvent
            }
        }
      }
  }

  private fun getBlobsForEvents(
    events: List<EthLogEvent<DataSubmittedV3>>
  ): SafeFuture<List<DataSubmittedEventAndBlobs>> {
    return SafeFuture.collectAll(
      events
        .map { dataSubmittedEvent ->
          transactionDetailsClient
            .getBlobVersionedHashesByTransactionHash(dataSubmittedEvent.log.transactionHash)
            .thenCompose(blobsFetcher::fetchBlobsByHash)
            .thenApply { blobs -> DataSubmittedEventAndBlobs(dataSubmittedEvent, blobs) }
        }.stream()
    )
  }

  private fun updateNodeWithBlobsAndVerifyState(
    dataSubmissions: List<DataSubmittedEventAndBlobs>,
    dataFinalizedV3: DataFinalizedV3
  ): SafeFuture<BlockNumberAndHash> {
    return blobDecompressor
      .decompress(
        startBlockNumber = dataFinalizedV3.startBlockNumber,
        blobs = dataSubmissions.flatMap { it.blobs }
      ).thenCompose { decompressedBlocks: List<BlockL1RecoveredData> ->
        log.debug("importing blocks={}", dataFinalizedV3.intervalString())
        blockImporterAndStateVerifier
          .importBlocks(decompressedBlocks)
          .thenCompose { importResult ->
            log.debug("imported blocks={}", dataFinalizedV3.intervalString())
            assertStateMatches(importResult, dataFinalizedV3)
          }
          .thenApply {
            BlockNumberAndHash(
              number = decompressedBlocks.last().blockNumber,
              hash = decompressedBlocks.last().blockHash
            )
          }
      }
  }

  private fun assertStateMatches(
    importResult: ImportResult,
    finalizedV3: DataFinalizedV3
  ): SafeFuture<Unit> {
    return if (importResult.zkStateRootHash.contentEquals(finalizedV3.finalStateRootHash)) {
      log.info(
        "state recovered up to block={} zkStateRootHash={} finalization={}",
        importResult.blockNumber,
        importResult.zkStateRootHash.encodeHex(),
        finalizedV3.intervalString()
      )
      SafeFuture.completedFuture(Unit)
    } else {
      log.error(
        "stateRootHash mismatch: finalization={} l1StateRootHash={} localStateRootHash={}",
        finalizedV3.intervalString(),
        finalizedV3.finalStateRootHash,
        importResult.zkStateRootHash.encodeHex()
      )
      stateRootMismatchFound = true
      SafeFuture.failedFuture(IllegalStateException("stateRootHash mismatch"))
    }
  }
}