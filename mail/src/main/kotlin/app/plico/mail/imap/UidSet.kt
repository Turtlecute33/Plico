package app.plico.mail.imap

/** A sorted set of UIDs stored as merged ranges. [MAX] stands for `*`. */
class UidSet private constructor(val ranges: List<LongRange>) {

    val isEmpty: Boolean get() = ranges.isEmpty()

    /** Number of UIDs. Only meaningful for sets without `*`. */
    val count: Long get() = ranges.sumOf { it.last - it.first + 1 }

    val min: Long? get() = ranges.firstOrNull()?.first
    val max: Long? get() = ranges.lastOrNull()?.last

    operator fun contains(uid: Long): Boolean {
        var lo = 0
        var hi = ranges.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val r = ranges[mid]
            when {
                uid < r.first -> hi = mid - 1
                uid > r.last -> lo = mid + 1
                else -> return true
            }
        }
        return false
    }

    /** True when every UID of [range] is in this set. */
    internal fun containsAll(range: LongRange): Boolean {
        if (range.isEmpty()) return true
        var lo = 0
        var hi = ranges.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val r = ranges[mid]
            when {
                range.first < r.first -> hi = mid - 1
                range.first > r.last -> lo = mid + 1
                else -> return range.last <= r.last
            }
        }
        return false
    }

    /** The UIDs of both sets. */
    fun union(other: UidSet): UidSet = when {
        other.isEmpty -> this
        isEmpty -> other
        else -> fromRanges(ranges + other.ranges)
    }

    /** All UIDs in ascending order. Do not call on a set that contains `*`. */
    fun toList(): List<Long> {
        require(max != MAX) { "open range" }
        return ranges.flatMap { it.toList() }
    }

    /** Splits into sets of at most [maxRanges] ranges, to keep command lines short. */
    fun chunks(maxRanges: Int = 500): List<UidSet> =
        if (ranges.size <= maxRanges) listOf(this) else ranges.chunked(maxRanges).map(::UidSet)

    override fun toString(): String = ranges.joinToString(",") { r ->
        val last = if (r.last == MAX) "*" else r.last.toString()
        if (r.first == r.last) last else "${r.first}:$last"
    }

    override fun equals(other: Any?) = other is UidSet && other.ranges == ranges
    override fun hashCode() = ranges.hashCode()

    companion object {
        const val MAX = Long.MAX_VALUE
        val EMPTY = UidSet(emptyList())

        fun of(uids: Iterable<Long>): UidSet = fromRanges(uids.map { it..it })

        fun of(vararg uids: Long): UidSet = of(uids.asIterable())

        /** `from:to`; `to = MAX` gives `from:*`. */
        fun range(from: Long, to: Long = MAX): UidSet = fromRanges(listOf(minOf(from, to)..maxOf(from, to)))

        /** Parses `1:5,8,10:*`. `n:m` with n > m is normalised. */
        fun parse(text: String): UidSet {
            if (text.isBlank()) return EMPTY
            return fromRanges(text.split(',').map { part ->
                val bounds = part.trim().split(':')
                val a = bound(bounds[0])
                val b = if (bounds.size > 1) bound(bounds[1]) else a
                minOf(a, b)..maxOf(a, b)
            })
        }

        private fun bound(s: String): Long = if (s == "*") MAX else s.toLong()

        private fun fromRanges(input: List<LongRange>): UidSet {
            if (input.isEmpty()) return EMPTY
            val sorted = input.sortedBy { it.first }
            val out = ArrayList<LongRange>()
            var cur = sorted[0]
            for (i in 1 until sorted.size) {
                val r = sorted[i]
                cur = if (cur.last != MAX && r.first <= cur.last + 1) cur.first..maxOf(cur.last, r.last)
                else if (cur.last == MAX) cur
                else { out += cur; r }
            }
            out += cur
            return UidSet(out)
        }
    }
}
