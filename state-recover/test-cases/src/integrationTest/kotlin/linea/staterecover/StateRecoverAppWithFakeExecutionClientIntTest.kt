package linea.staterecover

import build.linea.clients.StateManagerClientV1
import build.linea.contract.l1.LineaContractVersion
import build.linea.contract.l1.LineaRollupSmartContractClientReadOnly
import build.linea.contract.l1.Web3JLineaRollupSmartContractClientReadOnly
import build.linea.domain.RetryConfig
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.vertx.core.Vertx
import io.vertx.junit5.VertxExtension
import linea.build.staterecover.clients.VertxTransactionDetailsClient
import linea.staterecover.clients.blobscan.BlobScanClient
import linea.staterecover.test.FakeExecutionLayerClient
import linea.staterecover.test.FakeStateManagerClient
import linea.web3j.Web3JLogsSearcher
import net.consensys.linea.BlockNumberAndHash
import net.consensys.linea.BlockParameter
import net.consensys.linea.jsonrpc.client.RequestRetryConfig
import net.consensys.linea.jsonrpc.client.VertxHttpJsonRpcClientFactory
import net.consensys.linea.testing.submission.AggregationAndBlobs
import net.consensys.linea.testing.submission.loadBlobsAndAggregationsSortedAndGrouped
import net.consensys.linea.testing.submission.submitBlobsAndAggregations
import net.consensys.zkevm.coordinator.clients.smartcontract.LineaRollupSmartContractClient
import net.consensys.zkevm.ethereum.ContractsManager
import net.consensys.zkevm.ethereum.Web3jClientManager
import net.consensys.zkevm.ethereum.waitForTxReceipt
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.net.URI
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

@ExtendWith(VertxExtension::class)
class StateRecoverAppWithFakeExecutionClientIntTest {
  private val log = LogManager.getLogger("test.case.StateRecoverAppWithFakeExecutionClientIntTest")
  private lateinit var stateRecoverApp: StateRecoverApp
  private lateinit var aggregationsAndBlobs: List<AggregationAndBlobs>
  private lateinit var executionLayerClient: FakeExecutionLayerClient
  private lateinit var fakeStateManagerClient: StateManagerClientV1
  private lateinit var transactionDetailsClient: TransactionDetailsClient
  private lateinit var lineaContractClient: LineaRollupSmartContractClientReadOnly

  private lateinit var contractClientForSubmittions: LineaRollupSmartContractClient
  private val testDataDir = "testdata/coordinator/prover/v3/"

  private val l1RpcUrl = "http://localhost:8445"
  private val blobScanUrl = "http://localhost:4001"

  @BeforeEach
  fun beforeEach(vertx: Vertx) {
    val jsonRpcFactory = VertxHttpJsonRpcClientFactory(vertx = vertx, meterRegistry = SimpleMeterRegistry())
    aggregationsAndBlobs = loadBlobsAndAggregationsSortedAndGrouped(
      blobsResponsesDir = "$testDataDir/compression/responses",
      aggregationsResponsesDir = "$testDataDir/aggregation/responses"
    )
    executionLayerClient = FakeExecutionLayerClient(
      headBlock = BlockNumberAndHash(number = 0uL, hash = ByteArray(32) { 0 }),
      initialStateRecoverStartBlockNumber = null,
      loggerName = "test.fake.clients.l1.fake-execution-layer"
    )
    fakeStateManagerClient = FakeStateManagerClient(blobRecords = aggregationsAndBlobs.flatMap { it.blobs })
    transactionDetailsClient = VertxTransactionDetailsClient.create(
      jsonRpcClientFactory = jsonRpcFactory,
      endpoint = URI(l1RpcUrl),
      retryConfig = RequestRetryConfig(
        backoffDelay = 10.milliseconds,
        timeout = 2.seconds
      ),
      logger = LogManager.getLogger("test.clients.l1.transaction-details")
    )

    val rollupDeploymentResult = ContractsManager.get()
      .deployLineaRollup(numberOfOperators = 2, contractVersion = LineaContractVersion.V6).get()

    lineaContractClient = Web3JLineaRollupSmartContractClientReadOnly(
      web3j = Web3jClientManager.buildL1Client(
        log = LogManager.getLogger("test.clients.l1.linea-contract"),
        requestResponseLogLevel = Level.INFO,
        failuresLogLevel = Level.WARN
      ),
      contractAddress = rollupDeploymentResult.contractAddress
    )
    val logsSearcher = Web3JLogsSearcher(
      vertx = vertx,
      web3jClient = Web3jClientManager.buildL1Client(
        log = LogManager.getLogger("test.clients.l1.events-fetcher"),
        requestResponseLogLevel = Level.TRACE,
        failuresLogLevel = Level.WARN
      ),
      Web3JLogsSearcher.Config(
        backoffDelay = 1.milliseconds,
        requestRetryConfig = RetryConfig.noRetries
      ),
      log = LogManager.getLogger("test.clients.l1.events-fetcher")
    )

    contractClientForSubmittions = rollupDeploymentResult.rollupOperatorClient
    val blobScanClient = BlobScanClient.create(
      vertx = vertx,
      endpoint = URI(blobScanUrl),
      requestRetryConfig = RequestRetryConfig(
        backoffDelay = 10.milliseconds,
        timeout = 2.seconds
      ),
      responseLogMaxSize = 1000u,
      logger = LogManager.getLogger("test.clients.l1.blobscan")
    )

    stateRecoverApp = StateRecoverApp(
      vertx = vertx,
      elClient = executionLayerClient,
      blobFetcher = blobScanClient,
      ethLogsSearcher = logsSearcher,
      stateManagerClient = fakeStateManagerClient,
      transactionDetailsClient = transactionDetailsClient,
      l1EventsPollingInterval = 5.seconds,
      blockHeaderStaticFields = BlockHeaderStaticFields.localDev,
      lineaContractClient = lineaContractClient,
      config = StateRecoverApp.Config(
        l1LatestSearchBlock = BlockParameter.Tag.LATEST,
        l1PollingInterval = 5.seconds,
        executionClientPollingInterval = 1.seconds,
        smartContractAddress = lineaContractClient.getAddress()
      )
    )
  }

  /*
  1. when state recovery disabled: enables with lastFinalizedBlock + 1
  1.1 no finalizations and start from genesis
  1.2 headBlockNumber > lastFinalizedBlock -> enable with headBlockNumber + 1
  1.3 headBlockNumber < lastFinalizedBlock -> enable with lastFinalizedBlock + 1

  2. when state recovery enabled:
  2.1 recoveryStartBlockNumber > headBlockNumber: pull for head block number until is reached and start recovery there
  2.2 recoveryStartBlockNumber <= headBlockNumber: resume recovery from headBlockNumber
  */
  @Test
  fun `when state recovery disabled and is starting from genesis`() {
    stateRecoverApp.start().get()

    val submissionTxHashes = submitBlobsAndAggregations(
      contractClient = contractClientForSubmittions,
      aggregationsAndBlobs = aggregationsAndBlobs,
      blobChunksSize = 6
    )

    Web3jClientManager.l1Client.waitForTxReceipt(
      txHash = submissionTxHashes.aggregationTxHashes.last(),
      timeout = 2.minutes
    )
    val lastAggregation = aggregationsAndBlobs.findLast { it.aggregation != null }!!.aggregation
    await()
      .atMost(1.minutes.toJavaDuration())
      .untilAsserted {
        assertThat(stateRecoverApp.lastProcessedFinalization?.event?.endBlockNumber)
          .isEqualTo(lastAggregation!!.endBlockNumber)
      }

    assertThat(executionLayerClient.lineaGetStateRecoveryStatus().get())
      .isEqualTo(
        StateRecoveryStatus(
          headBlockNumber = lastAggregation!!.endBlockNumber,
          stateRecoverStartBlockNumber = 1UL
        )
      )
  }

  @Test
  fun `when recovery is disabled and headBlock is before lastFinalizedBlock resumes from lastFinalizedBlock+1`() {
    val finalizationToResumeFrom = aggregationsAndBlobs.get(1).aggregation!!
    // assert that the finalization event to resume from has at least 1 middle block
    assertThat(finalizationToResumeFrom.endBlockNumber)
      .isGreaterThan(finalizationToResumeFrom.startBlockNumber + 1UL)
      .withFailMessage("finalizationEventToResumeFrom must at least 3 blocks for this test")

    val finalizationsBeforeCutOff = aggregationsAndBlobs
      .filter { it.aggregation != null }
      .filter { it.aggregation!!.endBlockNumber < finalizationToResumeFrom.startBlockNumber }

    val finalizationsAfterCutOff = aggregationsAndBlobs
      .filter { it.aggregation != null }
      .filter { it.aggregation!!.startBlockNumber >= finalizationToResumeFrom.startBlockNumber }

    log.debug("finalizations={}", aggregationsAndBlobs.map { it.aggregation?.intervalString() })

    submitBlobsAndAggregations(
      contractClient = contractClientForSubmittions,
      aggregationsAndBlobs = finalizationsBeforeCutOff,
      blobChunksSize = 6
    ).also { submissionTxHashes ->
      Web3jClientManager.l1Client.waitForTxReceipt(
        txHash = submissionTxHashes.aggregationTxHashes.last(),
        timeout = 2.minutes
      )
    }

    executionLayerClient.lastImportedBlock = BlockNumberAndHash(
      number = 1UL,
      hash = ByteArray(32) { 0 }
    )

    stateRecoverApp.start().get()

    val lastAggregation = aggregationsAndBlobs.findLast { it.aggregation != null }!!.aggregation
    await()
      .atMost(4.minutes.toJavaDuration())
      .pollInterval(1.seconds.toJavaDuration())
      .untilAsserted {
        assertThat(executionLayerClient.stateRecoverStatus).isEqualTo(
          StateRecoveryStatus(
            headBlockNumber = 1UL,
            stateRecoverStartBlockNumber = finalizationsBeforeCutOff.last().aggregation!!.endBlockNumber + 1UL
          )
        )
        log.info("stateRecoverStatus={}", executionLayerClient.stateRecoverStatus)
      }

    // simulate that execution client has synced up to the last finalized block through P2P network
    executionLayerClient.lastImportedBlock = BlockNumberAndHash(
      number = finalizationToResumeFrom.endBlockNumber,
      hash = ByteArray(32) { 0 }
    )

    // continue finalizing the rest of the aggregations
    submitBlobsAndAggregations(
      contractClient = contractClientForSubmittions,
      aggregationsAndBlobs = finalizationsAfterCutOff,
      blobChunksSize = 6
    ).also { submissionTxHashes ->
      Web3jClientManager.l1Client.waitForTxReceipt(
        txHash = submissionTxHashes.aggregationTxHashes.last(),
        timeout = 2.minutes
      )
    }

    await()
      .atMost(1.minutes.toJavaDuration())
      .pollInterval(1.seconds.toJavaDuration())
      .untilAsserted {
        assertThat(
          executionLayerClient.lineaGetStateRecoveryStatus().get()
            .also { println(it) }
        )
          .isEqualTo(
            StateRecoveryStatus(
              headBlockNumber = lastAggregation!!.endBlockNumber,
              stateRecoverStartBlockNumber = finalizationsBeforeCutOff.last().aggregation!!.endBlockNumber + 1UL
            )
          )
      }
  }
}