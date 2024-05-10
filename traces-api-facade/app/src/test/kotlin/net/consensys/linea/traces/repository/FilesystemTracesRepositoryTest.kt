package net.consensys.linea.traces.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import io.vertx.core.Vertx
import net.consensys.linea.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.io.path.Path

class FilesystemTracesRepositoryTest {
  private val tracesDirectory = Path("../../testdata/traces/raw")
  private val fakeTraces: String = """{"ADD":[]}"""
  private lateinit var repository: FilesystemTracesRepository
  private val objectMapper = ObjectMapper()

  @BeforeEach
  fun beforeEach() {
    repository =
      FilesystemTracesRepository(Vertx.vertx(), tracesDirectory, ".gz") { _ -> fakeTraces }
  }

  @Test
  fun getTraces_fileNotAvailable() {
    val result = repository.getTraces(99911u).get()

    assertThat(result).isInstanceOf(Err::class.java)
    result as Err
    assertThat(result.error.errorType).isEqualTo(ErrorType.TRACES_UNAVAILABLE)
    assertThat(result.error.errorDetail).isEqualTo("Traces not available for block 99911.")
  }

  @Test
  fun getTraces_tracesFound() {
    val result = repository.getTraces(13673u).get()

    assertThat(result).isInstanceOf(Ok::class.java)
    result as Ok
    assertThat(result.value).isEqualTo(fakeTraces)
  }
}
