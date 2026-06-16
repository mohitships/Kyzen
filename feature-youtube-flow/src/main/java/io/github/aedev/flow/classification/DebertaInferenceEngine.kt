package io.github.aedev.flow.classification

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.LongBuffer

object DebertaInferenceEngine {

    private const val TAG = "DebertaInferenceEngine"
    private const val MODEL_PATH = "onnx/model_int8.onnx"
    private const val INFERENCE_TIMEOUT_MS = 500L
    private const val MAX_SEQ_LEN = 128

    private val HYPOTHESIS_PRODUCTIVE = "educational"
    private val HYPOTHESIS_ENTERTAINMENT = "entertainment"

    private val sessionMutex = Mutex()

    @Volatile
    private var ortEnv: OrtEnvironment? = null
    @Volatile
    private var ortSession: OrtSession? = null
    private var tokenizer: SentencePieceTokenizer? = null
    @Volatile
    private var isClosed: Boolean = false
    @Volatile
    private var isDisabled: Boolean = false

    suspend fun classify(
        context: Context,
        title: String,
        channel: String
    ): KeywordClassifier.ClassificationResult? {
        if (isDisabled) return null
        return withContext(Dispatchers.Default) {
            sessionMutex.withLock {
                if (isDisabled || isClosed) return@withLock null
                try {
                    if (ortSession == null || tokenizer == null) {
                        if (!initialize(context)) return@withLock null
                    }

                    val result = withTimeoutOrNull(INFERENCE_TIMEOUT_MS) {
                        runInference(title, channel)
                    }

                    if (result == null) {
                        Log.w(
                            TAG,
                            "Inference exceeded ${INFERENCE_TIMEOUT_MS}ms — disabling ONNX"
                        )
                        isDisabled = true
                        closeSessionInternal()
                        return@withLock null
                    }

                    result
                } catch (e: OutOfMemoryError) {
                    Log.e(TAG, "OOM during DeBERTa inference — disabling ONNX", e)
                    isDisabled = true
                    closeSessionInternal()
                    null
                } catch (e: Exception) {
                    Log.e(TAG, "DeBERTa inference failed", e)
                    null
                }
            }
        }
    }

    private suspend fun initialize(context: Context): Boolean {
        return try {
            tokenizer = SentencePieceTokenizer.create(context)
            if (tokenizer == null) {
                Log.w(TAG, "Tokenizer initialization failed — ONNX disabled")
                isDisabled = true
                return false
            }

            val modelBytes = context.assets.open(MODEL_PATH).use { it.readBytes() }
            ortEnv = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(1)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            ortSession = ortEnv!!.createSession(modelBytes, opts)
            Log.i(TAG, "ONNX session created successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ONNX", e)
            isDisabled = true
            false
        }
    }

    private fun runInference(
        title: String,
        channel: String
    ): KeywordClassifier.ClassificationResult? {
        val tok = tokenizer ?: return null
        val env = ortEnv ?: return null
        val session = ortSession ?: return null

        val premise = "Channel: $channel | Title: $title"

        val (prodIds, prodMask) =
            tok.encodeWithSpecialTokens(premise, HYPOTHESIS_PRODUCTIVE, MAX_SEQ_LEN)
        val (entIds, entMask) =
            tok.encodeWithSpecialTokens(premise, HYPOTHESIS_ENTERTAINMENT, MAX_SEQ_LEN)

        val shape = longArrayOf(1L, MAX_SEQ_LEN.toLong())

        OnnxTensor.createTensor(env, LongBuffer.wrap(prodIds), shape).use { prodInput ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(prodMask), shape).use { prodAttention ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(entIds), shape).use { entInput ->
                    OnnxTensor.createTensor(env, LongBuffer.wrap(entMask), shape).use { entAttention ->

                        val prodResult = session.run(
                            mapOf("input_ids" to prodInput, "attention_mask" to prodAttention)
                        )
                        val entResult = session.run(
                            mapOf("input_ids" to entInput, "attention_mask" to entAttention)
                        )

                        prodResult.use { pr ->
                            entResult.use { er ->
                                val prodLogits =
                                    (pr.get("logits") as OnnxTensor).floatBuffer
                                val entLogits =
                                    (er.get("logits") as OnnxTensor).floatBuffer

                                val prodEntail = prodLogits.get(0)
                                val entEntail = entLogits.get(0)

                                Log.i(
                                    TAG,
                                    "Prod entail=$prodEntail, Ent entail=$entEntail"
                                )

                                return if (prodEntail > entEntail) {
                                    KeywordClassifier.ClassificationResult(
                                        "PRODUCTIVE",
                                        prodEntail
                                    )
                                } else {
                                    KeywordClassifier.ClassificationResult(
                                        "ENTERTAINMENT",
                                        entEntail
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    suspend fun closeOnMemoryModerate() {
        sessionMutex.withLock { closeSessionInternal() }
    }

    fun closeOnMemoryCritical() {
        try {
            closeSessionInternal()
        } catch (_: Exception) {}
    }

    suspend fun closeOnPlayerExit() {
        sessionMutex.withLock { closeSessionInternal() }
    }

    private fun closeSessionInternal() {
        try {
            ortSession?.close()
        } catch (_: Exception) {}
        try {
            ortEnv?.close()
        } catch (_: Exception) {}
        ortSession = null
        ortEnv = null
        tokenizer = null
        isClosed = true
        Log.i(TAG, "ONNX session closed")
    }

    fun resetDisabledFlag() {
        isDisabled = false
        isClosed = false
    }
}
