package com.doge.ipdf

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarModelTest {

    private fun feed(vararg events: String): String = buildString {
        append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\n")
        events.forEach { append(it) }
        append("END:VCALENDAR\r\n")
    }

    private fun event(
        uid: String,
        summary: String,
        start: String,
        end: String,
        location: String = "ICT Cubes 002 (2320|002)",
        status: String? = null,
    ): String = buildString {
        append("BEGIN:VEVENT\r\n")
        append("UID:$uid\r\n")
        if (status != null) append("STATUS:$status\r\n")
        append("SUMMARY:$summary\r\n")
        append("DTSTART:$start\r\n")
        append("DTEND:$end\r\n")
        if (location.isNotBlank()) append("LOCATION:$location\r\n")
        append("END:VEVENT\r\n")
    }

    @Test
    fun parsesFoldedRwthEventAndCourse() {
        // The real RWTHonline feed folds long SUMMARY lines after a space and uses UTC stamps.
        val ics = feed(
            "BEGIN:VEVENT\r\n" +
                "UID:6574168@rwth-aachen.de\r\n" +
                "STATUS:CONFIRMED\r\n" +
                "SUMMARY:61.00019 Microprocessor architectures UE\\, Standardgruppe\r\n" +
                "DTSTART:20261029T153000Z\r\n" +
                "DTEND:20261029T161500Z\r\n" +
                "LOCATION:ICT Cubes 002 (2320|002)\r\n" +
                "END:VEVENT\r\n",
        )
        val events = IcsCalendar.parse(ics, ZoneId.of("Europe/Berlin"))
        assertEquals(1, events.size)
        val event = events.single()
        assertEquals("61.00019 Microprocessor architectures UE", event.summary)
        assertEquals("Microprocessor architectures", event.courseTitle)
        assertEquals("microprocessor architectures", event.courseKey)
        assertEquals("ICT Cubes 002 (2320|002)", event.location)
        assertEquals(Instant.parse("2026-10-29T15:30:00Z").toEpochMilli(), event.startAt)
        assertEquals(Instant.parse("2026-10-29T16:15:00Z").toEpochMilli(), event.endAt)
        assertEquals("45m", formatEventDuration(event.startAt, event.endAt))
    }

    @Test
    fun groupsLectureAndExerciseNumbersUnderTheSameCourseTitle() {
        val ics = feed(
            event(
                "lecture",
                "61.00004 Microprocessor architectures VO\\, Standardgruppe",
                "20261029T133000Z",
                "20261029T150000Z",
            ),
            event(
                "exercise",
                "61.00019 Microprocessor architectures UE\\, Standardgruppe",
                "20261029T153000Z",
                "20261029T161500Z",
            ),
        )
        val events = IcsCalendar.parse(ics, ZoneId.of("Europe/Berlin"))
        assertEquals(2, events.size)
        assertEquals(1, events.map { it.courseKey }.distinct().size)
        val courses = IcsCalendar.courses(events)
        assertEquals(1, courses.size)
        assertEquals(listOf("61.00004", "61.00019"), courses.single().codes)
        assertEquals(2, courses.single().eventCount)
    }

    @Test
    fun keepsCommasInCourseTitleAndUnescapesText() {
        val ics = feed(
            event(
                "power",
                "61.49878 Power Electronics - Control\\, Synthesis and Applications VU\\, Standardgruppe",
                "20261218T113000Z",
                "20261218T130000Z",
                location = "H08 (1385|219)",
            ),
        )
        val event = IcsCalendar.parse(ics, ZoneId.of("Europe/Berlin")).single()
        assertEquals("Power Electronics - Control, Synthesis and Applications", event.courseTitle)
        assertEquals("61.49878 Power Electronics - Control, Synthesis and Applications VU", event.summary)
        assertEquals("H08 (1385|219)", event.location)
    }

    @Test
    fun parsesAllDayAndCancelledEvents() {
        val ics = feed(
            "BEGIN:VEVENT\r\n" +
                "UID:holiday\r\n" +
                "SUMMARY:61.99999 Holiday\r\n" +
                "DTSTART;VALUE=DATE:20261224\r\n" +
                "DTEND;VALUE=DATE:20261225\r\n" +
                "END:VEVENT\r\n",
            event(
                "cancelled",
                "61.00019 Microprocessor architectures UE\\, Standardgruppe",
                "20261029T153000Z",
                "20261029T161500Z",
                status = "CANCELLED",
            ),
        )
        val events = IcsCalendar.parse(ics, ZoneId.of("Europe/Berlin"))
        assertEquals(1, events.size)
        assertTrue(events.single().allDay)
        assertEquals(
            LocalDate.of(2026, 12, 24).atStartOfDay(ZoneId.of("Europe/Berlin")).toInstant().toEpochMilli(),
            events.single().startAt,
        )
    }

    @Test
    fun parsesTzidStartAndDurationFallback() {
        val ics = feed(
            "BEGIN:VEVENT\r\n" +
                "UID:local\r\n" +
                "SUMMARY:61.12345 Local course VU\\, Standardgruppe\r\n" +
                "DTSTART;TZID=\"Europe/Berlin\":20261102T093000\r\n" +
                "DURATION:PT1H30M\r\n" +
                "END:VEVENT\r\n",
        )
        val event = IcsCalendar.parse(ics, ZoneId.of("Europe/Berlin")).single()
        assertEquals(
            LocalDateTime.of(2026, 11, 2, 9, 30).atZone(ZoneId.of("Europe/Berlin")).toInstant().toEpochMilli(),
            event.startAt,
        )
        assertEquals("1h30m", formatEventDuration(event.startAt, event.endAt))
    }

    @Test
    fun todoStatusAndChronologicalOrder() {
        val zone = ZoneId.of("Europe/Berlin")
        val now = LocalDateTime.of(2026, 10, 27, 12, 0).atZone(zone).toInstant().toEpochMilli()
        fun due(hour: Int, day: Int = 27) =
            LocalDateTime.of(2026, 10, day, hour, 0).atZone(zone).toInstant().toEpochMilli()

        assertEquals(TodoStatus.OVERDUE, todoStatus(due(9), now, zone))
        assertEquals(TodoStatus.TODAY, todoStatus(due(18), now, zone))
        assertEquals(TodoStatus.UPCOMING, todoStatus(due(9, 28), now, zone))

        val tasks = listOf(
            TodoTask("a", "Late", "", due(18), 0),
            TodoTask("b", "Urgent", "", due(9), 0),
            TodoTask("c", "Tomorrow", "", due(9, 28), 0),
            TodoTask("d", "Done", "", due(8), 0, completedAt = 1),
        )
        assertEquals(listOf("b", "a", "c"), activeTodos(tasks).map { it.id })
        assertEquals(listOf("d"), completedTodos(tasks).map { it.id })
    }

    @Test
    fun autoMatchUsesCourseCodeAndTitleTokens() {
        val courses = listOf(
            CalendarCourse(
                key = "microprocessor architectures",
                title = "Microprocessor architectures",
                codes = listOf("61.00004", "61.00019"),
                eventCount = 2,
            ),
            CalendarCourse(
                key = "battery storage systems",
                title = "Battery Storage Systems",
                codes = listOf("61.02798"),
                eventCount = 3,
            ),
            CalendarCourse(
                key = "einführung in high-performance computing",
                title = "Einführung in High-Performance Computing",
                codes = listOf("12.00019"),
                eventCount = 28,
            ),
        )
        val folders = listOf(
            CourseFolderOption("26ws-61.00004-Micro", "Micro", "26ws", 5),
            CourseFolderOption("Battery Storage Systems-26ws", "Battery Storage Systems", "26ws", 8),
            CourseFolderOption("26ws-HPC", "HPC", "26ws", 3),
        )
        val mapping = autoMatchFolders(folders, courses, emptyMap())
        assertEquals("microprocessor architectures", mapping["26ws-61.00004-Micro"])
        assertEquals("battery storage systems", mapping["Battery Storage Systems-26ws"])
        assertEquals("einführung in high-performance computing", mapping["26ws-HPC"])
    }
}
