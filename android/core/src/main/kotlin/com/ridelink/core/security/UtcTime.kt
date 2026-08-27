package com.ridelink.core.security

/**
 * A UTC instant expressed the one way X.509 needs it, and nowhere else in RideLink.
 *
 * **This is the project's only wall-clock type, and that is deliberate.** CLAUDE.md's
 * monotonic-clocks-only rule exists to stop *scheduling* from depending on a clock that can jump;
 * certificate validity is not scheduling — RFC 5280 defines `notBefore`/`notAfter` in wall-clock
 * terms and there is no monotonic alternative. Nothing else may use this type, and it never
 * reads a clock itself: [epochSeconds] is always supplied by the caller.
 *
 * Civil-date conversion is done here rather than with `java.time` / `DateFormatter` so that
 * Kotlin and Swift run the *same* arithmetic and cannot drift apart over a locale, a calendar or
 * a time zone. The algorithm is Howard Hinnant's `days_from_civil` / `civil_from_days`, which is
 * exact for the proleptic Gregorian calendar. `RideLinkCore.Security.UtcTime` mirrors it.
 *
 * `MagicNumber` is suppressed for the whole type deliberately. Its literals are not tunable
 * quantities: `12` is how many months a year has, `substring(2, 4)` is where the month sits in
 * `YYMMDDHHMMSSZ`, and `146097` is a term of a published algorithm. Naming them would put a layer
 * of indirection between the code and the specification it implements, which is the opposite of
 * what makes this reviewable.
 */
@Suppress("MagicNumber")
@JvmInline
value class UtcTime(
    val epochSeconds: Long,
) : Comparable<UtcTime> {
    /** `YYMMDDHHMMSSZ`, the DER UTCTime form (RFC 5280 §4.1.2.5.1). */
    val utcTimeString: String
        get() {
            val (year, month, day) = civilFromDays(Math.floorDiv(epochSeconds, SECONDS_PER_DAY))
            require(year in MIN_UTCTIME_YEAR..MAX_UTCTIME_YEAR) {
                "UTCTime cannot represent year $year; RFC 5280 requires GeneralizedTime from 2050"
            }
            var secondOfDay = Math.floorMod(epochSeconds, SECONDS_PER_DAY)
            val hour = secondOfDay / SECONDS_PER_HOUR
            secondOfDay -= hour * SECONDS_PER_HOUR
            val minute = secondOfDay / SECONDS_PER_MINUTE
            val second = secondOfDay - minute * SECONDS_PER_MINUTE
            return "%02d%02d%02d%02d%02d%02dZ".format(year % 100, month, day, hour, minute, second)
        }

    override fun compareTo(other: UtcTime): Int = epochSeconds.compareTo(other.epochSeconds)

    /**
     * Adds whole calendar years, clamping 29 February onto 28 February in a non-leap target year.
     * Used only to derive a certificate's `notAfter` from its `notBefore`; "ten years" is a
     * calendar span, not a fixed number of seconds, and pretending otherwise drifts by days.
     */
    fun plusYears(years: Int): UtcTime {
        val days = Math.floorDiv(epochSeconds, SECONDS_PER_DAY)
        val secondOfDay = Math.floorMod(epochSeconds, SECONDS_PER_DAY)
        val (year, month, day) = civilFromDays(days)
        val targetYear = year + years
        val clampedDay = minOf(day, daysInMonth(targetYear, month))
        return UtcTime(daysFromCivil(targetYear, month, clampedDay) * SECONDS_PER_DAY + secondOfDay)
    }

    fun minusSeconds(seconds: Long): UtcTime = UtcTime(epochSeconds - seconds)

    /** Inclusive on both ends, matching how X.509 validity windows are read. */
    fun isWithin(
        notBefore: UtcTime,
        notAfter: UtcTime,
    ): Boolean = this >= notBefore && this <= notAfter

    companion object {
        const val SECONDS_PER_MINUTE: Long = 60
        const val SECONDS_PER_HOUR: Long = 60 * SECONDS_PER_MINUTE
        const val SECONDS_PER_DAY: Long = 24 * SECONDS_PER_HOUR

        /** RFC 5280 §4.1.2.5.1: UTCTime covers 1950–2049; from 2050 a certificate must use GeneralizedTime. */
        const val MIN_UTCTIME_YEAR: Long = 1950
        const val MAX_UTCTIME_YEAR: Long = 2049

        private const val UTCTIME_LENGTH = 13
        private const val CENTURY_PIVOT = 50 // YY >= 50 means 19YY, otherwise 20YY

        /** Parses `YYMMDDHHMMSSZ`. Returns null rather than throwing: the input can come off the wire. */
        @Suppress("ReturnCount") // one early-out per malformed-field guard reads clearer than nesting
        fun parse(utcTimeString: String): UtcTime? {
            if (utcTimeString.length != UTCTIME_LENGTH || utcTimeString.last() != 'Z') return null
            if (!utcTimeString.dropLast(1).all { it in '0'..'9' }) return null
            val twoDigitYear = utcTimeString.substring(0, 2).toLong()
            val year = if (twoDigitYear >= CENTURY_PIVOT) 1900 + twoDigitYear else 2000 + twoDigitYear
            val month = utcTimeString.substring(2, 4).toLong()
            val day = utcTimeString.substring(4, 6).toLong()
            val hour = utcTimeString.substring(6, 8).toLong()
            val minute = utcTimeString.substring(8, 10).toLong()
            val second = utcTimeString.substring(10, 12).toLong()
            if (month !in 1..12 || day < 1 || day > daysInMonth(year, month)) return null
            if (hour > 23 || minute > 59 || second > 59) return null
            return UtcTime(
                daysFromCivil(year, month, day) * SECONDS_PER_DAY +
                    hour * SECONDS_PER_HOUR + minute * SECONDS_PER_MINUTE + second,
            )
        }

        fun isLeapYear(year: Long): Boolean = (year % 4 == 0L && year % 100 != 0L) || year % 400 == 0L

        fun daysInMonth(
            year: Long,
            month: Long,
        ): Long =
            when (month) {
                2L -> if (isLeapYear(year)) 29L else 28L
                4L, 6L, 9L, 11L -> 30L
                else -> 31L
            }

        /**
         * Howard Hinnant's `days_from_civil`: days since 1970-01-01 for a proleptic Gregorian date.
         * Exact integer arithmetic, no floating point, no calendar library — so Kotlin and Swift
         * cannot disagree.
         */
        @Suppress("MagicNumber") // the constants are the published algorithm's; renaming them obscures it
        fun daysFromCivil(
            year: Long,
            month: Long,
            day: Long,
        ): Long {
            val y = if (month <= 2) year - 1 else year
            val era = Math.floorDiv(y, 400L)
            val yearOfEra = y - era * 400 // [0, 399]
            val dayOfYear = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1 // [0, 365]
            val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear // [0, 146096]
            return era * 146097 + dayOfEra - 719468
        }

        /** Howard Hinnant's `civil_from_days`, the exact inverse of [daysFromCivil]. */
        @Suppress("MagicNumber") // as above
        fun civilFromDays(days: Long): Triple<Long, Long, Long> {
            val z = days + 719468
            val era = Math.floorDiv(z, 146097L)
            val dayOfEra = z - era * 146097 // [0, 146096]
            val yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36524 - dayOfEra / 146096) / 365 // [0, 399]
            val year = yearOfEra + era * 400
            val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100) // [0, 365]
            val monthPrime = (5 * dayOfYear + 2) / 153 // [0, 11]
            val day = dayOfYear - (153 * monthPrime + 2) / 5 + 1 // [1, 31]
            val month = if (monthPrime < 10) monthPrime + 3 else monthPrime - 9 // [1, 12]
            return Triple(if (month <= 2) year + 1 else year, month, day)
        }
    }
}
