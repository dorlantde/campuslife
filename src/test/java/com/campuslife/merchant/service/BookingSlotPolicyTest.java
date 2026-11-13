package com.campuslife.merchant.service;

import com.campuslife.merchant.mapper.model.BarberScheduleRow;
import com.campuslife.merchant.vo.BookingSlot;
import com.campuslife.merchant.vo.BusinessHourView;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

class BookingSlotPolicyTest {
    private static final LocalDate DATE = LocalDate.of(2026, 10, 22);
    private static final Instant EARLY = at(DATE.minusDays(1), "18:00");

    @ParameterizedTest @CsvSource({"-1,09:00", "0,09:00", "1,09:15"})
    void preciseTwoHourBoundaryIsInclusiveWithoutTruncatingTheClock(long offsetMillis, String first) {
        var now = at(DATE, "07:00").plusMillis(offsetMillis);
        var slots = calculate(now, 30, List.of(hour("09:00", "10:00")), List.of(shift("09:00", "10:00")));
        assertThat(slots.getFirst().startAt()).isEqualTo(at(DATE, first));
        assertThat(slots.getLast().endAt()).isEqualTo(at(DATE, "10:00"));
    }

    @ParameterizedTest @CsvSource({"15,4", "30,3", "45,2", "60,1", "240,0"})
    void fullDurationMustFitButStartsAdvanceByFifteenMinutes(int duration, int expected) {
        var slots = calculate(EARLY, duration, List.of(hour("09:00", "10:00")), List.of(shift("09:00", "10:00")));
        assertThat(slots).hasSize(expected);
        for (int i = 0; i < slots.size(); i++) {
            assertThat(slots.get(i).startAt()).isEqualTo(at(DATE, "09:00").plusSeconds(i * 15L * 60));
            assertThat(Duration.between(slots.get(i).startAt(), slots.get(i).endAt())).isEqualTo(Duration.ofMinutes(duration));
        }
    }

    @Test void roundsToWallClockGridAndNeverCrossesLunchOrClosingTime() {
        var slots = calculate(EARLY, 30, List.of(hour("09:10", "12:00"), hour("14:00", "17:40")), List.of(shift("08:00", "18:00")));
        assertThat(slots.getFirst()).isEqualTo(slot("09:15", "09:45"));
        assertThat(slots.getLast()).isEqualTo(slot("17:00", "17:30"));
        assertThat(slots).contains(slot("11:30", "12:00"), slot("14:00", "14:30"));
        assertThat(slots).doesNotContain(slot("09:00", "09:30"), slot("11:45", "12:15"), slot("13:45", "14:15"), slot("17:15", "17:45"));
    }

    @Test void anUnbrokenBusinessDayCannotBridgeAShiftGap() {
        var slots = calculate(EARLY, 30, List.of(hour("09:00", "18:00")), List.of(shift("09:00", "10:00"), shift("10:15", "11:00")));
        assertThat(slots).containsExactly(slot("09:00", "09:30"), slot("09:15", "09:45"), slot("09:30", "10:00"), slot("10:15", "10:45"), slot("10:30", "11:00"));
    }

    @Test void duplicateSourceRowsDoNotDuplicateOrReorderCandidateAlternatives() {
        var slots = calculate(EARLY, 30, List.of(hour("09:00", "10:00"), hour("09:00", "10:00")), List.of(shift("09:00", "10:00"), shift("09:00", "10:00")));
        assertThat(slots).containsExactly(slot("09:00", "09:30"), slot("09:15", "09:45"), slot("09:30", "10:00"));
    }

    @Test void localMidnightIsReturnedOnThePreviousUtcDate() {
        var slots = calculate(EARLY, 30, List.of(hour("00:00", "01:00")), List.of(shift("00:00", "01:00")));
        assertThat(slots.getFirst().startAt()).isEqualTo(Instant.parse("2026-10-21T16:00:00Z"));
        assertThat(slots.getLast().endAt()).isEqualTo(Instant.parse("2026-10-21T17:00:00Z"));
    }

    @Test void noCandidateCanEndOnTheNextCampusDateEvenWithAMalformedSourceShift() {
        var shift = new BarberScheduleRow(1, DATE, utc(at(DATE, "23:00")), utc(at(DATE.plusDays(1), "00:00")));
        var slots = calculate(EARLY, 15, List.of(hour("23:00", "23:59")), List.of(shift));
        assertThat(slots).containsExactly(slot("23:00", "23:15"), slot("23:15", "23:30"), slot("23:30", "23:45"));
    }

    @ParameterizedTest @ValueSource(strings = {"noHours", "noSchedule", "restDay", "wrongServiceDate", "tooLate"})
    void missingCoverageAndExpiredStartsProduceNoCandidates(String mode) {
        var hours = List.of(hour("09:00", "10:00")); var shifts = List.of(shift("09:00", "10:00")); var now = EARLY;
        switch (mode) {
            case "noHours" -> hours = List.of();
            case "noSchedule" -> shifts = List.of();
            case "restDay" -> hours = List.of(new BusinessHourView(DATE.plusDays(1).getDayOfWeek().getValue(), "09:00", "10:00"));
            case "wrongServiceDate" -> shifts = List.of(new BarberScheduleRow(1, DATE.plusDays(1), shifts.getFirst().startAt(), shifts.getFirst().endAt()));
            case "tooLate" -> now = at(DATE, "07:45");
            default -> throw new IllegalStateException(mode);
        }
        assertThat(calculate(now, 30, hours, shifts)).isEmpty();
    }

    @ParameterizedTest @CsvSource({"-1,false", "0,true", "6,true", "7,false"})
    void windowUsesShanghaiDateRatherThanUtcDate(int offset, boolean valid) {
        var now = Instant.parse("2026-10-21T16:00:00Z"); var date = DATE.plusDays(offset);
        if (valid) { assertThat(BookingSlotPolicy.date(date.toString(), now)).isEqualTo(date); }
        else { assertThatThrownBy(() -> BookingSlotPolicy.date(date.toString(), now)).isInstanceOf(com.campuslife.common.exception.BusinessException.class); }
    }

    private List<BookingSlot> calculate(Instant now, int duration, List<BusinessHourView> hours, List<BarberScheduleRow> shifts) { return BookingSlotPolicy.candidates(DATE, now, duration, hours, shifts); }
    private static BusinessHourView hour(String start, String end) { return new BusinessHourView(DATE.getDayOfWeek().getValue(), start, end); }
    private static BarberScheduleRow shift(String start, String end) { return new BarberScheduleRow(1, DATE, utc(at(DATE, start)), utc(at(DATE, end))); }
    private static BookingSlot slot(String start, String end) { return new BookingSlot(at(DATE, start), at(DATE, end)); }
    private static Instant at(LocalDate date, String time) { return date.atTime(LocalTime.parse(time)).atZone(SchedulePolicy.CAMPUS_ZONE).toInstant(); }
    private static LocalDateTime utc(Instant time) { return LocalDateTime.ofInstant(time, ZoneOffset.UTC); }
}
