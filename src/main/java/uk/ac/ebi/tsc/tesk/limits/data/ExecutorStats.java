package uk.ac.ebi.tsc.tesk.limits.data;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static java.time.temporal.ChronoUnit.DAYS;

/**
 * Statistics of a single Executor Job used in calculating resource limits
 *
 * @author aniewielska
 * @since 13/08/2018
 */
@Builder
@Getter
public class ExecutorStats {

    private int number;
    private Instant startDateTime;
    private Instant endDateTime;
    private Instant retrievalTime;
    private BigDecimal cpu;
    private BigDecimal memoryGB;

    private BigDecimal getCalculatedDurationInHours(Instant now) {

        if (this.startDateTime == null) {
            return BigDecimal.ZERO;
        }
        Instant beginningOfMonth = getBeginningOfMonthForInstant(now);
        Instant startDateOrFirst = this.startDateTime.isBefore(beginningOfMonth) ? beginningOfMonth : this.startDateTime;
        Instant endDateOrNow = this.endDateTime != null ? this.endDateTime : now;
        if (endDateOrNow.isBefore(startDateOrFirst)) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(Duration.between(startDateOrFirst, endDateOrNow).getSeconds()).divide(new BigDecimal(3600), 4, RoundingMode.HALF_UP);
    }

    public ResourceUsage getResourceUsage(Instant now) {
        BigDecimal duration = this.getCalculatedDurationInHours(now);
        return new ResourceUsage(duration, this.cpu.multiply(duration), this.memoryGB.multiply(duration), BigDecimal.ZERO);
    }

    public ExecutorResourceUsage toExecutorResourceUsage(Instant now) {
        return ExecutorResourceUsage.builder().data(this.copy()).resourceUsage(this.getResourceUsage(now)).build();
    }

    private static Instant getBeginningOfMonthForInstant(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC).truncatedTo(DAYS).withDayOfMonth(1).toInstant(ZoneOffset.UTC);
    }

    public boolean isActiveOrEndedInRetrievalMonth() {
        return endDateTime == null || endDateTime.isAfter(getBeginningOfMonthForInstant(retrievalTime));
    }

    public ExecutorStats copy() {
        return ExecutorStats.builder().
                number(number).
                startDateTime(startDateTime).
                endDateTime(endDateTime).
                retrievalTime(retrievalTime).
                cpu(cpu).
                memoryGB(memoryGB).build();
    }

}
