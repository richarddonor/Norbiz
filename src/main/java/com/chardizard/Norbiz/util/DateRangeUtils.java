package com.chardizard.Norbiz.util;

import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/** Parses "yyyy-MM-dd" query params into inclusive UTC day-boundary Instants
 * for date-range filtering. */
public final class DateRangeUtils {

    private DateRangeUtils() {
    }

    public static Instant startOfDayUtc(String isoDate) {
        if (!StringUtils.hasText(isoDate)) return null;
        return LocalDate.parse(isoDate.trim()).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    public static Instant endOfDayUtc(String isoDate) {
        if (!StringUtils.hasText(isoDate)) return null;
        return LocalDate.parse(isoDate.trim()).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minusMillis(1);
    }
}
