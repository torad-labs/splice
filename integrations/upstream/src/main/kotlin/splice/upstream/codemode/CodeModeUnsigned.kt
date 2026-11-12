package splice.upstream.codemode

import java.math.BigInteger

/** Code Mode's u64 controls and usize fields on every supported 64-bit native host share this bound. */
internal object CodeModeUnsigned {
    public val maximum: BigInteger = BigInteger.ONE.shiftLeft(Long.SIZE_BITS).subtract(BigInteger.ONE)
}
