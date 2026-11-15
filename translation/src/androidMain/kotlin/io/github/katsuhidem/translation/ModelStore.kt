package io.github.katsuhidem.translation

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** A model file that can be downloaded. [sha256] is also the model's revision in cache keys. */
data class ModelSpec(
    val id: String,
    val displayName: String,
    val fileName: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
)

/** Models evaluated in Phase 0c (docs/spike/TRANSLATE_LOG.md). Both are under the PLaMo Community License. */
object ModelCatalog {
    val PLAMO3_0_8B = ModelSpec(
        id = "plamo3-0.8b-q4km",
        displayName = "PLaMo 3 Translate 0.8B (Q4_K_M)",
        fileName = "plamo-3-translate-0.8b-Q4_K_M.gguf",
        url = "https://huggingface.co/pfnet/plamo-3-translate-0.8b-GGUF/resolve/main/" +
            "plamo-3-translate-0.8b-Q4_K_M.gguf",
        sha256 = "70891ec70dc7ab4205745a38307867c2881b99239f20327927633abfe4bc9ec6",
        sizeBytes = 573_126_848,
    )
    val PLAMO3_2B = ModelSpec(
        id = "plamo3-2b-q4km",
        displayName = "PLaMo 3 Translate 2B (Q4_K_M)",
        fileName = "plamo-3-translate-2b-Q4_K_M.gguf",
        url = "https://huggingface.co/pfnet/plamo-3-translate-2b-GGUF/resolve/main/" +
            "plamo-3-translate-2b-Q4_K_M.gguf",
        sha256 = "1ffd9073afcc70e35ccc942428aa2b73d7aa2b9ea779bf504b775369e0276c13",
        sizeBytes = 1_807_203_040,
    )

    /** The default for titles and article text; 2B is the optional high-quality model. */
    val DEFAULT = PLAMO3_0_8B
    val ALL = listOf(PLAMO3_0_8B, PLAMO3_2B)

    const val LICENSE_NAME = "PLaMo Community License"
    const val LICENSE_URL = "https://plamo.preferredai.jp/info/plamo-community-license-ja"
}

sealed interface ModelState {
    data object NotInstalled : ModelState

    /** [totalBytes] is the full file size, [bytes] what is on disk so far (kept across restarts). */
    data class Downloading(val bytes: Long, val totalBytes: Long) : ModelState
    data object Verifying : ModelState
    data object Installed : ModelState
    data class Failed(val message: String) : ModelState
}

/**
 * Downloads, keeps and deletes model files.
 *
 * - A download writes to "<name>.part" and resumes from its size with an
 *   HTTP Range request, so an interrupted download continues.
 * - The finished file is checked against the SHA-256 in [ModelSpec]; a file
 *   that does not match is deleted.
 * - A verified file gets a "<name>.sha256" marker. Its content is the
 *   model's revision used in cache keys, readable without loading the model.
 *
 * Files live under the app's no-backup directory: they are large and can be
 * downloaded again.
 */
class ModelStore(
    private val dir: File,
    private val client: OkHttpClient = defaultClient(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val log = Logger.withTag("ModelStore")
    private val locks = HashMap<String, Mutex>()
    private val states = HashMap<String, MutableStateFlow<ModelState>>()

    fun state(spec: ModelSpec): StateFlow<ModelState> = stateFlow(spec).asStateFlow()

    /** The installed model file, or null. */
    fun file(spec: ModelSpec): File? = modelFile(spec).takeIf { isInstalled(spec) }

    /** Revision of the installed model (the file's SHA-256), or null when it is not installed. */
    fun modelRev(spec: ModelSpec): String? =
        if (isInstalled(spec)) runCatching { markerFile(spec).readText().trim() }.getOrNull() else null

    fun isInstalled(spec: ModelSpec): Boolean = modelFile(spec).isFile && markerFile(spec).isFile

    /** Bytes of an unfinished download on disk. */
    fun partialBytes(spec: ModelSpec): Long = partFile(spec).takeIf { it.isFile }?.length() ?: 0L

    /**
     * Downloads [spec] (continuing an earlier attempt) and verifies it.
     * Returns true when the model is installed. Cancelling keeps the partial file.
     */
    suspend fun download(spec: ModelSpec): Boolean = lockFor(spec).withLock {
        withContext(dispatcher) {
            val state = stateFlow(spec)
            if (isInstalled(spec)) {
                state.value = ModelState.Installed
                return@withContext true
            }
            try {
                dir.mkdirs()
                fetch(spec, state)
                state.value = ModelState.Verifying
                val part = partFile(spec)
                val actual = sha256(part)
                if (!actual.equals(spec.sha256, ignoreCase = true)) {
                    part.delete()
                    throw IOException("checksum mismatch; the downloaded file was deleted")
                }
                if (!part.renameTo(modelFile(spec))) throw IOException("could not move the model into place")
                markerFile(spec).writeText(actual.lowercase())
                log.i { "download ${spec.id}: verified and installed" }
                state.value = ModelState.Installed
                true
            } catch (e: CancellationException) {
                state.value = restingState(spec)
                throw e
            } catch (e: Exception) {
                log.w { "download of ${spec.id} failed: ${e.message}" }
                state.value = ModelState.Failed(e.message ?: "download failed")
                false
            }
        }
    }

    suspend fun delete(spec: ModelSpec) = lockFor(spec).withLock {
        withContext(dispatcher) {
            markerFile(spec).delete()
            modelFile(spec).delete()
            partFile(spec).delete()
            stateFlow(spec).value = ModelState.NotInstalled
        }
    }

    // ---- internals ----

    private suspend fun fetch(spec: ModelSpec, state: MutableStateFlow<ModelState>) {
        val part = partFile(spec)
        var have = if (part.isFile) part.length() else 0L
        if (have > spec.sizeBytes) {
            part.delete()
            have = 0
        }
        if (have == spec.sizeBytes) return // complete; only the check is left

        val request = Request.Builder().url(spec.url)
            .apply { if (have > 0) header("Range", "bytes=$have-") }
            .build()
        client.newCall(request).execute().use { response ->
            val onDisk = have
            when {
                response.code == HTTP_PARTIAL && have > 0 -> Unit
                response.code == HTTP_OK -> {
                    // The server ignored the range (or this is a fresh start): begin again.
                    have = 0
                }
                else -> throw IOException("unexpected HTTP ${response.code}")
            }
            log.i { "download ${spec.id}: on_disk=$onDisk http=${response.code} continue_from=$have" }
            state.value = ModelState.Downloading(have, spec.sizeBytes)
            RandomAccessFile(part, "rw").use { out ->
                out.setLength(have)
                out.seek(have)
                val source = response.body.byteStream()
                val buffer = ByteArray(BUFFER_SIZE)
                var sinceReport = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val n = source.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    have += n
                    sinceReport += n
                    if (sinceReport >= REPORT_EVERY_BYTES) {
                        sinceReport = 0
                        state.update { ModelState.Downloading(have, spec.sizeBytes) }
                    }
                }
            }
        }
        if (have != spec.sizeBytes) throw IOException("download ended early ($have of ${spec.sizeBytes} bytes)")
    }

    private suspend fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                coroutineContext.ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun restingState(spec: ModelSpec): ModelState = when {
        isInstalled(spec) -> ModelState.Installed
        partialBytes(spec) > 0 -> ModelState.Downloading(partialBytes(spec), spec.sizeBytes)
        else -> ModelState.NotInstalled
    }

    private fun stateFlow(spec: ModelSpec) = synchronized(states) {
        states.getOrPut(spec.id) { MutableStateFlow(restingState(spec)) }
    }

    private fun lockFor(spec: ModelSpec) = synchronized(locks) { locks.getOrPut(spec.id) { Mutex() } }

    private fun modelFile(spec: ModelSpec) = File(dir, spec.fileName)
    private fun partFile(spec: ModelSpec) = File(dir, spec.fileName + ".part")
    private fun markerFile(spec: ModelSpec) = File(dir, spec.fileName + ".sha256")

    companion object {
        private const val HTTP_OK = 200
        private const val HTTP_PARTIAL = 206
        private const val BUFFER_SIZE = 256 * 1024
        private const val REPORT_EVERY_BYTES = 4L * 1024 * 1024

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
