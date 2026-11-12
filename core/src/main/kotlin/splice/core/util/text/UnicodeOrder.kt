package splice.core.util.text

/** Compare well-formed strings by Unicode scalar values, including supplementary characters. */
public object UnicodeOrder {
    public val scalarValues: Comparator<String> = Comparator(::compare)

    private fun compare(left: String, right: String): Int {
        var first = 0
        var second = 0
        while (first < left.length && second < right.length) {
            val leftPoint = left.codePointAt(first)
            val rightPoint = right.codePointAt(second)
            if (leftPoint != rightPoint) return leftPoint.compareTo(rightPoint)
            first += Character.charCount(leftPoint)
            second += Character.charCount(rightPoint)
        }
        return (left.length - first).compareTo(right.length - second)
    }
}
