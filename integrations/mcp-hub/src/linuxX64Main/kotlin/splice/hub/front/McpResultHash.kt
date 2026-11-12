package splice.hub.front

/** SHA-256 over a validated callback ID, which fits one padded block. Matches the JVM receipt filename. */
internal object McpResultHash {
    fun filename(callbackId: String): String {
        val bytes = callbackId.encodeToByteArray()
        require(bytes.size <= MAX_SINGLE_BLOCK_BYTES)
        val words = IntArray(ROUND_COUNT)
        bytes.forEachIndexed { index, byte ->
            val shift = LAST_BYTE_SHIFT - index % Int.SIZE_BYTES * Byte.SIZE_BITS
            words[index / Int.SIZE_BYTES] = words[index / Int.SIZE_BYTES] or ((byte.toInt() and BYTE_MASK) shl shift)
        }
        val paddingShift = LAST_BYTE_SHIFT - bytes.size % Int.SIZE_BYTES * Byte.SIZE_BITS
        words[bytes.size / Int.SIZE_BYTES] = words[bytes.size / Int.SIZE_BYTES] or (PADDING_BIT shl paddingShift)
        words[LENGTH_WORD] = bytes.size * Byte.SIZE_BITS
        expand(words)
        val work = INITIAL.copyOf()
        repeat(ROUND_COUNT) { round -> compress(work, words[round], CONSTANTS[round]) }
        return INITIAL.indices.joinToString("") {
            (INITIAL[it] + work[it]).toUInt().toString(HEX_RADIX).padStart(HEX_WORD_CHARS, '0')
        } + ".json"
    }

    private fun expand(words: IntArray) {
        for (index in BLOCK_WORDS until ROUND_COUNT) {
            val left = words[index - SMALL_SIGMA0_WORD_BACK]
            val right = words[index - SMALL_SIGMA1_WORD_BACK]
            val s0 = left.rotateRight(
                SMALL_SIGMA0_R1,
            ) xor left.rotateRight(SMALL_SIGMA0_R2) xor (left ushr SMALL_SIGMA0_SHIFT)
            val s1 = right.rotateRight(
                SMALL_SIGMA1_R1,
            ) xor right.rotateRight(SMALL_SIGMA1_R2) xor (right ushr SMALL_SIGMA1_SHIFT)
            words[index] = words[index - BLOCK_WORDS] + s0 + words[index - SCHEDULE_SUM_WORD_BACK] + s1
        }
    }

    private fun compress(work: IntArray, word: Int, constant: Int) {
        val upper = work[E].rotateRight(
            BIG_SIGMA1_R1,
        ) xor work[E].rotateRight(BIG_SIGMA1_R2) xor work[E].rotateRight(BIG_SIGMA1_R3)
        val choose = (work[E] and work[F]) xor (work[E].inv() and work[G])
        val t1 = work[H] + upper + choose + constant + word
        val lower = work[A].rotateRight(
            BIG_SIGMA0_R1,
        ) xor work[A].rotateRight(BIG_SIGMA0_R2) xor work[A].rotateRight(BIG_SIGMA0_R3)
        val majority = (work[A] and work[B]) xor (work[A] and work[C]) xor (work[B] and work[C])
        for (index in H downTo B) work[index] = work[index - 1]
        work[E] += t1
        work[A] = t1 + lower + majority
    }

    private const val ROUND_COUNT = 64
    private const val BLOCK_WORDS = 16
    private const val LENGTH_WORD = BLOCK_WORDS - 1
    private const val MAX_SINGLE_BLOCK_BYTES = 55
    private const val LAST_BYTE_SHIFT = Int.SIZE_BITS - Byte.SIZE_BITS
    private const val BYTE_MASK = 255
    private const val PADDING_BIT = 0x80
    private const val HEX_RADIX = 16
    private const val HEX_WORD_CHARS = 8
    private const val SMALL_SIGMA0_WORD_BACK = 15
    private const val SMALL_SIGMA1_WORD_BACK = 2
    private const val SCHEDULE_SUM_WORD_BACK = 7
    private const val SMALL_SIGMA0_R1 = 7
    private const val SMALL_SIGMA0_R2 = 18
    private const val SMALL_SIGMA0_SHIFT = 3
    private const val SMALL_SIGMA1_R1 = 17
    private const val SMALL_SIGMA1_R2 = 19
    private const val SMALL_SIGMA1_SHIFT = 10
    private const val BIG_SIGMA0_R1 = 2
    private const val BIG_SIGMA0_R2 = 13
    private const val BIG_SIGMA0_R3 = 22
    private const val BIG_SIGMA1_R1 = 6
    private const val BIG_SIGMA1_R2 = 11
    private const val BIG_SIGMA1_R3 = 25
    private const val A = 0
    private const val B = 1
    private const val C = 2
    private const val E = 4
    private const val F = 5
    private const val G = 6
    private const val H = 7

    // SHA-256's standard initial words and round table are data, decoded once without native crypto dependencies.
    private const val INITIAL_HEX = "6a09e667 bb67ae85 3c6ef372 a54ff53a 510e527f 9b05688c 1f83d9ab 5be0cd19"
    private val INITIAL = INITIAL_HEX.split(' ').map { it.toUInt(HEX_RADIX).toInt() }.toIntArray()
    private val CONSTANTS = (
        "428a2f98 71374491 b5c0fbcf e9b5dba5 3956c25b 59f111f1 923f82a4 ab1c5ed5 " +
            "d807aa98 12835b01 243185be 550c7dc3 72be5d74 80deb1fe 9bdc06a7 c19bf174 " +
            "e49b69c1 efbe4786 0fc19dc6 240ca1cc 2de92c6f 4a7484aa 5cb0a9dc 76f988da " +
            "983e5152 a831c66d b00327c8 bf597fc7 c6e00bf3 d5a79147 06ca6351 14292967 " +
            "27b70a85 2e1b2138 4d2c6dfc 53380d13 650a7354 766a0abb 81c2c92e 92722c85 " +
            "a2bfe8a1 a81a664b c24b8b70 c76c51a3 d192e819 d6990624 f40e3585 106aa070 " +
            "19a4c116 1e376c08 2748774c 34b0bcb5 391c0cb3 4ed8aa4a 5b9cca4f 682e6ff3 " +
            "748f82ee 78a5636f 84c87814 8cc70208 90befffa a4506ceb bef9a3f7 c67178f2"
        ).split(' ').map { it.toUInt(HEX_RADIX).toInt() }.toIntArray()
}
