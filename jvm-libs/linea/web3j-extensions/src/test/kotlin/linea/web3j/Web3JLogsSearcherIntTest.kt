package linea.web3j

import build.linea.domain.EthLog
import build.linea.domain.RetryConfig
import build.linea.jsonrpc.FakeJsonRpcServer
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import io.vertx.core.Vertx
import linea.SearchDirection
import net.consensys.encodeHex
import net.consensys.fromHexString
import net.consensys.linea.BlockParameter.Companion.toBlockParameter
import net.consensys.linea.jsonrpc.JsonRpcError
import net.consensys.toHexString
import net.consensys.toHexStringUInt256
import org.apache.logging.log4j.LogManager
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.web3j.protocol.Web3j
import org.web3j.protocol.http.HttpService
import java.net.URI
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

class Web3JLogsSearcherIntTest {
  private lateinit var web3jClient: Web3j
  private lateinit var logsClient: Web3JLogsSearcher
  private lateinit var vertx: Vertx
  private lateinit var wireMockServer: WireMockServer
  private lateinit var fakeJsonRpcServer: FakeJsonRpcServer
  private val address = "0x508ca82df566dcd1b0de8296e70a96332cd644ec"
  private val log = LogManager.getLogger("test.case.Web3JLogsSearcherIntTest")

  @BeforeEach
  fun beforeEach() {
    vertx = Vertx.vertx()
  }

  @AfterEach
  fun tearDown() {
    vertx.close()
  }

  private fun setupClientWithWireMockServer(
    retryConfig: RetryConfig = RetryConfig.noRetries
  ) {
    wireMockServer = WireMockServer(WireMockConfiguration.options().dynamicPort())
    wireMockServer.start()

    web3jClient = Web3j.build(HttpService(URI("http://127.0.0.1:" + wireMockServer.port()).toURL().toString()))
    vertx = Vertx.vertx()
    logsClient = Web3JLogsSearcher(
      vertx,
      web3jClient,
      config = Web3JLogsSearcher.Config(
        backoffDelay = 1.milliseconds,
        requestRetryConfig = retryConfig
      )
    )
  }

  private fun setupClientWithFakeJsonRpcServer(
    retryConfig: RetryConfig = RetryConfig.noRetries
  ) {
    fakeJsonRpcServer = setUpFakeLogsServer()
    logsClient = Web3JLogsSearcher(
      vertx,
      web3jClient = Web3j.build(HttpService(URI("http://127.0.0.1:" + fakeJsonRpcServer.bindedPort).toString())),
      config = Web3JLogsSearcher.Config(
        backoffDelay = 1.milliseconds,
        requestRetryConfig = retryConfig
      ),
      log = LogManager.getLogger("test.case.Web3JLogsSearcher")
    )
  }

  private fun setUpFakeLogsServer(): FakeJsonRpcServer {
    return FakeJsonRpcServer(
      vertx = vertx,
      serverName = "fake-execution-layer-log-searcher",
      recordRequestsResponses = true
    ).apply {
      /**
       * eth_getLogs request example
       * {
       *     "jsonrpc": "2.0",
       *     "method": "eth_getLogs",
       *     "params": [{
       *         "topics": ["0xa0262dc79e4ccb71ceac8574ae906311ae338aa4a2044fd4ec4b99fad5ab60cb"],
       *         "fromBlock": "earliest",
       *         "toBlock": "latest",
       *         "address": ["0xCf7Ed3AccA5a467e9e704C703E8D87F634fB0Fc9"]
       *     }],
       *  }
       */
      @Suppress("UNCHECKED_CAST")
      this.handle("eth_getLogs", { request ->
        val logsFilter = (request.params as List<Any>)[0] as Map<String, Any>
        val fromBlock = ULong.fromHexString(logsFilter["fromBlock"] as String)
        val toBlock = ULong.fromHexString(logsFilter["toBlock"] as String)
        val topics = logsFilter["topics"] as List<String>

        val logs = generateLogsForBlockRange(fromBlock.toInt(), toBlock.toInt(), topic = topics[0])
        log.debug("eth_getLogs fromBlock={} toBlock={} topics={} logs={}", fromBlock, toBlock, topics[0], logs)
        logs
      })
    }
  }

  private fun replyEthGetLogsWith(statusCode: Int, responseBody: String) {
    wireMockServer.stubFor(
      post(urlEqualTo("/"))
        .withRequestBody(containing("eth_getLogs"))
        .willReturn(
          aResponse()
            .withStatus(statusCode)
            .withBody(responseBody)
            .withHeader("Content-Type", "application/json")
        )
    )
  }

  @Test
  fun `when eth_getLogs returns json-rpc error shall return failed promise`() {
    setupClientWithWireMockServer()
    replyEthGetLogsWith(
      statusCode = 200,
      responseBody = """
        {
          "jsonrpc": "2.0",
          "error": {
            "code": -32000,
            "message": "Error: unable to retrieve logs"
          },
          "id": 1
        }
      """.trimIndent()
    )

    assertThatThrownBy {
      logsClient.getLogs(
        0UL.toBlockParameter(),
        20UL.toBlockParameter(),
        address = address,
        topics = emptyList()
      ).get()
    }
      .hasCauseInstanceOf(RuntimeException::class.java)
      .hasMessageContaining("json-rpc error: code=-32000 message=Error: unable to retrieve logs")
  }

  @Test
  fun `when eth_getLogs returns invalid json-rpc result shall return error instead of infinite retry`() {
    setupClientWithWireMockServer()
    replyEthGetLogsWith(
      statusCode = 200,
      responseBody = """
       {
          "jsonrpc": "2.0",
          "id": 1,
          "result": [{
                  "address": "0x508ca82df566dcd1b0de8296e70a96332cd644ec",
                  "blockHash": "0x216a74dcf",
                  "blockNumber": "0x864b52",
                  "data": "0x",
                  "logIndex": "0x0",
                  "removed": false,
                  "topics": ["0xe856c2b8bd4eb0027ce32eeaf595c21b0b6b4644b326e5b7bd80a1cf8db72e6c"],
                  "transactionHash": "0xfcff12cba7002ec38f391f2452",
                  "transactionIndex": "0x0"
           }]
      }
      """.trimIndent()
    )

    assertThatThrownBy {
      logsClient.getLogs(
        0UL.toBlockParameter(),
        20UL.toBlockParameter(),
        address = address,
        topics = emptyList()
      ).get()
    }
  }

  @Test
  fun `when eth_getLogs request fails shall retry request until it succeeds`() {
    setupClientWithFakeJsonRpcServer(
      retryConfig = RetryConfig(
        backoffDelay = 1.milliseconds,
        maxRetries = 4u
      )
    )

    fakeJsonRpcServer.handle("eth_getLogs", { _ ->
      // simulate 2 failures
      log.debug("eth_getLogs callCount=${fakeJsonRpcServer.callCountByMethod("eth_getLogs")}")
      if (fakeJsonRpcServer.callCountByMethod("eth_getLogs") < 2) {
        throw JsonRpcError.internalError().asException()
      } else {
        generateLogsForBlockRange(fromBlock = 10, toBlock = 15)
      }
    })

    val getLogsFuture = logsClient.getLogs(
      0UL.toBlockParameter(),
      20UL.toBlockParameter(),
      address = address,
      topics = emptyList()
    )

    getLogsFuture.get().also { logs ->
      assertThat(logs).hasSize(6)
    }
  }

  @Test
  fun `when eth_getLogs gets an HTTP error shall return failed promise`() {
    setupClientWithWireMockServer()

    replyEthGetLogsWith(
      statusCode = 500,
      responseBody = "Internal Server Error"
    )

    assertThatThrownBy {
      logsClient.getLogs(
        0UL.toBlockParameter(),
        20UL.toBlockParameter(),
        address = address,
        topics = emptyList()
      ).get()
    }
      .hasCauseInstanceOf(org.web3j.protocol.exceptions.ClientConnectionException::class.java)
      .hasMessageContaining("Invalid response received: 500; Internal Server Error")
  }

  @Test
  fun `when eth_getLogs gets a DNS error shall return failed promise`() {
    val randomHostname = "nowhere-${Random.nextBytes(20).encodeHex()}.local"
    web3jClient = Web3j.build(HttpService("http://$randomHostname:1234"))
    vertx = Vertx.vertx()
    logsClient = Web3JLogsSearcher(
      vertx,
      web3jClient,
      config = Web3JLogsSearcher.Config(
        backoffDelay = 1.milliseconds,
        requestRetryConfig = RetryConfig.noRetries
      )
    )

    assertThatThrownBy {
      logsClient.getLogs(
        0UL.toBlockParameter(),
        20UL.toBlockParameter(),
        address = address,
        topics = emptyList()
      ).get()
    }
      .hasCauseInstanceOf(java.net.UnknownHostException::class.java)
      .hasMessageContaining("$randomHostname")
    vertx.close()
  }

  private fun generateLogJson(
    blockNumber: Int,
    topic: String = "0x",
    transactionHash: String = "0x"
  ): Map<String, Any> {
    val topics = listOf(
      topic,
      blockNumber.toULong().toHexStringUInt256()
    )
    return mapOf(
      "address" to "0x",
      "blockHash" to "${blockNumber.toULong().toHexStringUInt256()}",
      "blockNumber" to "${blockNumber.toULong().toHexString()}",
      "data" to "0x",
      "logIndex" to "0x0",
      "removed" to false,
      "topics" to topics,
      "transactionHash" to transactionHash,
      "transactionIndex" to "0x0"
    )
  }

  private fun generateLogsForBlockRange(
    fromBlock: Int,
    toBlock: Int,
    stepSize: Int = 1,
    topic: String = "0x"
  ): List<Map<String, Any>> {
    return (fromBlock..toBlock step stepSize)
      .map {
        generateLogJson(
          blockNumber = it,
          topic = topic
        )
      }
  }

  private fun shallContinueToSearch(
    ethLog: EthLog,
    targetNumber: ULong
  ): SearchDirection? {
    val number = ULong.fromHexString(ethLog.topics[1].encodeHex())
    return when {
      number < targetNumber -> SearchDirection.FORWARD
      number > targetNumber -> SearchDirection.BACKWARD
      else -> null
    }
  }

//  fun assertCalledInCorrectOrder(targetNumber: ULong): (EthLog) -> SearchDirection? {
//    var prevDirection: SearchDirection? = null
//    var prevNumber: ULong? = null
//    return { ethLog ->
//      val number = ULong.fromHexString(ethLog.topics[1].encodeHex())
//      val direction = when {
//        number < targetNumber -> SearchDirection.FORWARD
//        number > targetNumber -> SearchDirection.BACKWARD
//        else -> null
//      }
//      if (prevDirection != null) {
//        assertThat(direction).isEqualTo(prevDirection)
//      }
//      prevDirection = direction
//      direction
//    }
//  }

  @Test
  fun `findLogs searches and returns log when found`() {
    setupClientWithFakeJsonRpcServer()

    (100..200)
      .forEach { number ->
        logsClient.findLog(
          fromBlock = 100UL.toBlockParameter(),
          toBlock = 200UL.toBlockParameter(),
          address = address,
          topics = listOf("0xffaabbcc"),
          chunkSize = 10,
          shallContinueToSearchPredicate = { ehtLog ->
            shallContinueToSearch(ehtLog, targetNumber = number.toULong())
          }
        )
          .get()
          .also { log ->
            assertThat(log).isNotNull()
            assertThat(log!!.topics[1].encodeHex()).isEqualTo(number.toULong().toHexStringUInt256())
          }
      }
  }

  @Test
  fun `findLogs searches L1 and returns null when not found - before range`() {
    setupClientWithFakeJsonRpcServer()

    logsClient.findLog(
      fromBlock = 100UL.toBlockParameter(),
      toBlock = 200UL.toBlockParameter(),
      address = address,
      topics = listOf("0xffaabbcc"),
      chunkSize = 10,
      shallContinueToSearchPredicate = { ehtLog ->
        shallContinueToSearch(ehtLog, targetNumber = 89UL)
      }
    )
      .get()
      .also { log ->
        assertThat(log).isNull()
        assertThat(fakeJsonRpcServer.callCountByMethod("eth_getLogs")).isBetween(1, 4)
      }
  }

  @Test
  fun `findLogs searches L1 and returns null when not found - after range`() {
    setupClientWithFakeJsonRpcServer()
    logsClient.findLog(
      fromBlock = 100UL.toBlockParameter(),
      toBlock = 200UL.toBlockParameter(),
      address = address,
      topics = listOf("0xffaabbcc"),
      chunkSize = 10,
      shallContinueToSearchPredicate = { ehtLog ->
        shallContinueToSearch(ehtLog, targetNumber = 250UL)
      }
    )
      .get()
      .also { log ->
        assertThat(log).isNull()
        assertThat(fakeJsonRpcServer.callCountByMethod("eth_getLogs")).isBetween(1, 4)
      }
  }
}