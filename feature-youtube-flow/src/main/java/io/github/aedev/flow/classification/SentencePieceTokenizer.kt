package io.github.aedev.flow.classification

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.Normalizer

class SentencePieceTokenizer private constructor(
    private val vocab: Map<String, Int>,
    private val idToPiece: Map<Int, String>,
    private val maxPieceLength: Int
) {
    companion object {
        private const val TAG = "SentencePieceTokenizer"
        private const val VOCAB_FILENAME = "onnx/tokenizer_vocab.json"

        const val PAD_ID = 0
        const val CLS_ID = 1
        const val SEP_ID = 2
        const val UNK_ID = 3

        suspend fun create(context: Context): SentencePieceTokenizer? = withContext(Dispatchers.IO) {
            try {
                val jsonStr = context.assets.open(VOCAB_FILENAME).bufferedReader().use { it.readText() }
                val root = JSONObject(jsonStr)
                val piecesArray = root.getJSONArray("pieces")

                val vocab = HashMap<String, Int>(piecesArray.length())
                val idToPiece = HashMap<Int, String>(piecesArray.length())
                var maxLen = 0

                for (i in 0 until piecesArray.length()) {
                    val entry = piecesArray.getJSONObject(i)
                    val piece = entry.getString("piece")
                    val id = entry.getInt("id")
                    vocab[piece] = id
                    idToPiece[id] = piece
                    if (piece.length > maxLen) maxLen = piece.length
                }

                Log.i(TAG, "Loaded ${vocab.size} pieces, max length $maxLen")
                SentencePieceTokenizer(vocab, idToPiece, maxLen)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load tokenizer vocabulary", e)
                null
            }
        }
    }

    private val trieRoot = TrieNode()
    private val bytePieceIds: IntArray

    init {
        for ((piece, id) in vocab) {
            insertIntoTrie(piece, id)
        }
        bytePieceIds = IntArray(256) { UNK_ID }
        for ((piece, id) in vocab) {
            if (piece.startsWith("<0x") && piece.endsWith(">") && piece.length == 6) {
                try {
                    val byteVal = piece.substring(3, 5).toInt(16)
                    if (byteVal in 0..255) bytePieceIds[byteVal] = id
                } catch (_: NumberFormatException) {}
            }
        }
    }

    fun encode(text: String): List<Int> {
        val normalized = normalize(text)
        val tokens = mutableListOf<Int>()
        var i = 0
        while (i < normalized.length) {
            var bestId = -1
            var bestLen = 0

            var node = trieRoot
            val limit = minOf(normalized.length, i + maxPieceLength)
            var j = i
            while (j < limit) {
                val child = node.children[normalized[j]]
                if (child == null) break
                node = child
                j++
                if (node.tokenId != null) {
                    bestId = node.tokenId!!
                    bestLen = j - i
                }
            }

            if (bestLen > 0) {
                tokens.add(bestId)
                i += bestLen
            } else {
                val bytes = normalized[i].toString().encodeToByteArray()
                for (b in bytes) {
                    val byteId = if (b.toInt() and 0xFF < 256) bytePieceIds[b.toInt() and 0xFF] else UNK_ID
                    tokens.add(if (byteId != UNK_ID) byteId else UNK_ID)
                }
                i++
            }
        }
        return tokens
    }

    fun encodeWithSpecialTokens(
        premise: String,
        hypothesis: String,
        maxLength: Int
    ): Pair<LongArray, LongArray> {
        val premiseIds = encode(premise)
        val hypothesisIds = encode(hypothesis)

        val inputIds = mutableListOf<Long>()
        inputIds.add(CLS_ID.toLong())
        inputIds.addAll(premiseIds.map { it.toLong() })
        inputIds.add(SEP_ID.toLong())
        inputIds.addAll(hypothesisIds.map { it.toLong() })
        inputIds.add(SEP_ID.toLong())

        val seqLen = minOf(inputIds.size, maxLength)
        val ids = LongArray(maxLength) { PAD_ID.toLong() }
        val mask = LongArray(maxLength) { 0L }

        for (i in 0 until seqLen) {
            ids[i] = inputIds[i]
            mask[i] = 1L
        }

        return Pair(ids, mask)
    }

    private fun normalize(text: String): String {
        val nfkc = Normalizer.normalize(text, Normalizer.Form.NFKC)
        val lower = nfkc.lowercase()
        val marked = lower.replace(" ", "\u2581")
        return if (!marked.startsWith("\u2581")) "\u2581$marked" else marked
    }

    private fun insertIntoTrie(piece: String, id: Int) {
        var node = trieRoot
        for (ch in piece) {
            var child = node.children[ch]
            if (child == null) {
                child = TrieNode()
                node.children[ch] = child
            }
            node = child
        }
        node.tokenId = id
    }

    private class TrieNode {
        val children = HashMap<Char, TrieNode>()
        var tokenId: Int? = null
    }
}
