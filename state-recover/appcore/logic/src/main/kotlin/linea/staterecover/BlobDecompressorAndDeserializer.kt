package linea.staterecover

import build.linea.staterecover.BlockL1RecoveredData
import build.linea.staterecover.TransactionL1RecoveredData
import io.vertx.core.Vertx
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import net.consensys.decodeHex
import net.consensys.linea.CommonDomainFunctions
import net.consensys.linea.async.toSafeFuture
import net.consensys.linea.blob.BlobDecompressor
import net.consensys.toULong
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.apache.tuweni.bytes.Bytes
import org.hyperledger.besu.ethereum.core.Block
import org.hyperledger.besu.ethereum.core.encoding.registry.BlockDecoder
import org.hyperledger.besu.ethereum.mainnet.MainnetBlockHeaderFunctions
import org.hyperledger.besu.ethereum.rlp.RLP
import tech.pegasys.teku.infrastructure.async.SafeFuture
import java.util.concurrent.Callable
import kotlin.jvm.optionals.getOrNull

interface BlobDecompressorAndDeserializer {
  /**
   * Decompresses the EIP4844 blobs and deserializes them into domain objects.
   */
  fun decompress(
    startBlockNumber: ULong,
    blobs: List<ByteArray>
  ): SafeFuture<List<BlockL1RecoveredData>>
}

data class BlockHeaderStaticFields(
  val coinbase: ByteArray,
  val gasLimit: ULong = 61_000_000UL,
  val difficulty: ULong = 2UL
) {
  companion object {
    val mainnet = BlockHeaderStaticFields(
      coinbase = "0x8F81e2E3F8b46467523463835F965fFE476E1c9E".decodeHex()
    )
    val sepolia = BlockHeaderStaticFields(
      coinbase = "0x4D517Aef039A48b3B6bF921e210b7551C8E37107".decodeHex()
    )
    val localDev = BlockHeaderStaticFields(
      coinbase = "0x6d976c9b8ceee705d4fe8699b44e5eb58242f484".decodeHex()
    )
  }
}

class BlobDecompressorToDomainV1(
  val decompressor: BlobDecompressor,
//  val chainId: ULong,
  val staticFields: BlockHeaderStaticFields,
  val vertx: Vertx,
  val logger: Logger = LogManager.getLogger(BlobDecompressorToDomainV1::class.java)
) : BlobDecompressorAndDeserializer {

  private val blockDecoder =
    BlockDecoder.builder().withTransactionDecoder({ NoSignatureTransactionDecoder() })
      .build()
  private val blockHeaderFunctions = MainnetBlockHeaderFunctions()

  override fun decompress(
    startBlockNumber: ULong,
    blobs: List<ByteArray>
  ): SafeFuture<List<BlockL1RecoveredData>> {
    var blockNumber = startBlockNumber
    val startTime = Clock.System.now()
    logger.debug("start decompressing blobs: startBlockNumber={} {} blobs", startBlockNumber, blobs.size)
    val decompressedBlobs = blobs.map { decompressor.decompress(it) }

    return SafeFuture
      .collectAll(decompressedBlobs.map(::decodeBlocksAsync).stream())
      .thenApply { blobsBlocks: List<List<Block>> ->
        blobsBlocks.flatten().map { block ->
          BlockL1RecoveredData(
            blockNumber = blockNumber++,
            blockHash = block.header.parentHash.toArray(),
            coinbase = staticFields.coinbase,
            blockTimestamp = Instant.fromEpochSeconds(block.header.timestamp),
            gasLimit = this.staticFields.gasLimit,
            difficulty = block.header.difficulty.asBigInteger.toULong(),
            transactions = block.body.transactions.map { transaction ->
              TransactionL1RecoveredData(
                type = transaction.type.serializedType.toUByte(),
                from = transaction.sender.toArray(),
                nonce = transaction.nonce.toULong(),
                gasLimit = transaction.gasLimit.toULong(),
                maxFeePerGas = transaction.maxFeePerGas.getOrNull()?.asBigInteger,
                maxPriorityFeePerGas = transaction.maxPriorityFeePerGas.getOrNull()?.asBigInteger,
                gasPrice = transaction.gasPrice.getOrNull()?.asBigInteger,
                to = transaction.to.getOrNull()?.toArray(),
                value = transaction.value.asBigInteger,
                data = transaction.data.getOrNull()?.toArray(),
                accessList = transaction.accessList.getOrNull()?.map { accessTuple ->
                  TransactionL1RecoveredData.AccessTuple(
                    address = accessTuple.address.toArray(),
                    storageKeys = accessTuple.storageKeys.map { it.toArray() }
                  )
                }
              )
            }
          )
        }
      }.thenPeek {
        val endTime = Clock.System.now()
        logger.debug(
          "blobs decompressed and serialized: duration={} {} blobs, blocks={}",
          endTime - startTime,
          blobs.size,
          CommonDomainFunctions.blockIntervalString(startBlockNumber, blockNumber - 1UL)
        )
      }
  }

  private fun decodeBlocksAsync(blocksRLP: ByteArray): SafeFuture<List<Block>> {
    return vertx.executeBlocking(
      Callable {
        decodeBlocks(blocksRLP)
      },
      false
    )
      .onFailure(logger::error)
      .toSafeFuture()
  }

  private fun decodeBlocks(blocksRLP: ByteArray): List<Block> {
    return rlpDecodeAsListOfBytes(blocksRLP)
      .map { blockRlp ->
        blockDecoder.decode(RLP.input(Bytes.wrap(blockRlp), true), blockHeaderFunctions)
      }
  }
}

internal fun rlpDecodeAsListOfBytes(rlpEncoded: ByteArray): List<ByteArray> {
  val decodedBytes = mutableListOf<ByteArray>()
  RLP.input(Bytes.wrap(rlpEncoded), true).also { rlpInput ->
    rlpInput.enterList()
    while (!rlpInput.isEndOfCurrentList) {
      decodedBytes.add(rlpInput.readBytes().toArray())
    }
    rlpInput.leaveList()
  }
  return decodedBytes
}