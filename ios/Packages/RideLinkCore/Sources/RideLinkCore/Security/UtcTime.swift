import Foundation

/// A UTC instant expressed the one way X.509 needs it, and nowhere else in RideLink.
///
/// **This is the project's only wall-clock type, and that is deliberate.** CLAUDE.md's
/// monotonic-clocks-only rule exists to stop *scheduling* from depending on a clock that can jump;
/// certificate validity is not scheduling — RFC 5280 defines `notBefore`/`notAfter` in wall-clock
/// terms and there is no monotonic alternative. Nothing else may use this type, and it never reads
/// a clock itself: `epochSeconds` is always supplied by the caller.
///
/// Civil-date conversion is done here rather than with `DateFormatter` / `Calendar` so that Swift
/// and Kotlin run the *same* arithmetic and cannot drift apart over a locale, a calendar or a time
/// zone. The algorithm is Howard Hinnant's `days_from_civil` / `civil_from_days`, exact for the
/// proleptic Gregorian calendar. `com.ridelink.core.security.UtcTime` mirrors it.
public struct UtcTime: Hashable, Comparable, Sendable {
    public let epochSeconds: Int64

    public init(_ epochSeconds: Int64) { self.epochSeconds = epochSeconds }

    public static let secondsPerMinute: Int64 = 60
    public static let secondsPerHour: Int64 = 60 * secondsPerMinute
    public static let secondsPerDay: Int64 = 24 * secondsPerHour

    /// RFC 5280 §4.1.2.5.1: UTCTime covers 1950–2049; from 2050 a certificate must use GeneralizedTime.
    public static let minUTCTimeYear: Int64 = 1950
    public static let maxUTCTimeYear: Int64 = 2049

    private static let utcTimeLength = 13
    private static let centuryPivot: Int64 = 50 // YY >= 50 means 19YY, otherwise 20YY

    /// `YYMMDDHHMMSSZ`, the DER UTCTime form (RFC 5280 §4.1.2.5.1).
    public var utcTimeString: String {
        let (year, month, day) = UtcTime.civilFromDays(UtcTime.floorDiv(epochSeconds, UtcTime.secondsPerDay))
        precondition(year >= UtcTime.minUTCTimeYear && year <= UtcTime.maxUTCTimeYear,
                     "UTCTime cannot represent year \(year); RFC 5280 requires GeneralizedTime from 2050")
        var secondOfDay = UtcTime.floorMod(epochSeconds, UtcTime.secondsPerDay)
        let hour = secondOfDay / UtcTime.secondsPerHour
        secondOfDay -= hour * UtcTime.secondsPerHour
        let minute = secondOfDay / UtcTime.secondsPerMinute
        let second = secondOfDay - minute * UtcTime.secondsPerMinute
        return String(format: "%02d%02d%02d%02d%02d%02dZ", year % 100, month, day, hour, minute, second)
    }

    public static func < (lhs: UtcTime, rhs: UtcTime) -> Bool { lhs.epochSeconds < rhs.epochSeconds }

    /// Adds whole calendar years, clamping 29 February onto 28 February in a non-leap target year.
    /// Used only to derive a certificate's `notAfter` from its `notBefore`; "ten years" is a
    /// calendar span, not a fixed number of seconds, and pretending otherwise drifts by days.
    public func plusYears(_ years: Int) -> UtcTime {
        let days = UtcTime.floorDiv(epochSeconds, UtcTime.secondsPerDay)
        let secondOfDay = UtcTime.floorMod(epochSeconds, UtcTime.secondsPerDay)
        let (year, month, day) = UtcTime.civilFromDays(days)
        let targetYear = year + Int64(years)
        let clampedDay = min(day, UtcTime.daysInMonth(year: targetYear, month: month))
        return UtcTime(UtcTime.daysFromCivil(year: targetYear, month: month, day: clampedDay)
            * UtcTime.secondsPerDay + secondOfDay)
    }

    public func minusSeconds(_ seconds: Int64) -> UtcTime { UtcTime(epochSeconds - seconds) }

    /// Inclusive on both ends, matching how X.509 validity windows are read.
    public func isWithin(notBefore: UtcTime, notAfter: UtcTime) -> Bool {
        self >= notBefore && self <= notAfter
    }

    /// Parses `YYMMDDHHMMSSZ`. Returns nil rather than trapping: the input can come off the wire.
    public static func parse(_ utcTimeString: String) -> UtcTime? {
        let characters = Array(utcTimeString)
        guard characters.count == utcTimeLength, characters.last == "Z" else { return nil }
        guard characters.dropLast().allSatisfy({ $0.isASCII && $0.isNumber }) else { return nil }
        func field(_ from: Int, _ to: Int) -> Int64 { Int64(String(characters[from ..< to]))! }
        let twoDigitYear = field(0, 2)
        let year = twoDigitYear >= centuryPivot ? 1900 + twoDigitYear : 2000 + twoDigitYear
        let month = field(2, 4), day = field(4, 6)
        let hour = field(6, 8), minute = field(8, 10), second = field(10, 12)
        guard (1 ... 12).contains(month), day >= 1, day <= daysInMonth(year: year, month: month) else { return nil }
        guard hour <= 23, minute <= 59, second <= 59 else { return nil }
        return UtcTime(daysFromCivil(year: year, month: month, day: day) * secondsPerDay
            + hour * secondsPerHour + minute * secondsPerMinute + second)
    }

    public static func isLeapYear(_ year: Int64) -> Bool {
        (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
    }

    public static func daysInMonth(year: Int64, month: Int64) -> Int64 {
        switch month {
        case 2: return isLeapYear(year) ? 29 : 28
        case 4, 6, 9, 11: return 30
        default: return 31
        }
    }

    /// Howard Hinnant's `days_from_civil`: days since 1970-01-01 for a proleptic Gregorian date.
    /// Exact integer arithmetic, no floating point, no calendar library — so Swift and Kotlin
    /// cannot disagree.
    public static func daysFromCivil(year: Int64, month: Int64, day: Int64) -> Int64 {
        let y = month <= 2 ? year - 1 : year
        let era = floorDiv(y, 400)
        let yearOfEra = y - era * 400 // [0, 399]
        let dayOfYear = (153 * (month > 2 ? month - 3 : month + 9) + 2) / 5 + day - 1 // [0, 365]
        let dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear // [0, 146096]
        return era * 146_097 + dayOfEra - 719_468
    }

    /// Howard Hinnant's `civil_from_days`, the exact inverse of `daysFromCivil`.
    public static func civilFromDays(_ days: Int64) -> (year: Int64, month: Int64, day: Int64) {
        let z = days + 719_468
        let era = floorDiv(z, 146_097)
        let dayOfEra = z - era * 146_097 // [0, 146096]
        let yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36524 - dayOfEra / 146_096) / 365 // [0, 399]
        let year = yearOfEra + era * 400
        let dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100) // [0, 365]
        let monthPrime = (5 * dayOfYear + 2) / 153 // [0, 11]
        let day = dayOfYear - (153 * monthPrime + 2) / 5 + 1 // [1, 31]
        let month = monthPrime < 10 ? monthPrime + 3 : monthPrime - 9 // [1, 12]
        return (month <= 2 ? year + 1 : year, month, day)
    }

    /// Floor division and modulus, matching Kotlin's `Math.floorDiv`/`floorMod`. Swift's `/` and
    /// `%` truncate toward zero, which gives the wrong answer for pre-1970 instants — a difference
    /// that would silently only show up in a certificate dated before the epoch.
    static func floorDiv(_ a: Int64, _ b: Int64) -> Int64 {
        let q = a / b
        return (a % b != 0 && (a < 0) != (b < 0)) ? q - 1 : q
    }

    static func floorMod(_ a: Int64, _ b: Int64) -> Int64 { a - floorDiv(a, b) * b }
}
