package com.doge.ipdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryModelTest {

    private fun entry(
        name: String,
        course: String = "HPC",
        courseFolder: String = "26ws-HPC",
        semester: String? = "26ws",
        type: DocType = DocType.LECTURE,
        folder: String = "Lecture",
    ) = LibraryEntry(
        uri = "content://test/$courseFolder/$folder/$name",
        name = name,
        course = course,
        courseFolder = courseFolder,
        semester = semester,
        type = type,
        relativeFolder = folder,
        size = 1024,
        modified = 0,
    )

    @Test
    fun semesterSuffixAndPrefix() {
        assertEquals(ParsedCourse("HPC", "26ws"), SemesterParser.parse("26ws-HPC"))
        assertEquals(ParsedCourse("HPC", "26ws"), SemesterParser.parse("HPC-26ws"))
        assertEquals(ParsedCourse("HPC", "26ws"), SemesterParser.parse("HPC WS26"))
        assertEquals(ParsedCourse("HPC", "26ws"), SemesterParser.parse("2026ws-HPC"))
        assertEquals(ParsedCourse("Power Electronics", "27ss"), SemesterParser.parse("Power Electronics-27ss"))
        assertEquals(ParsedCourse("VLSI - Fundamentals", "26ws"), SemesterParser.parse("VLSI - Fundamentals_WS26"))
    }

    @Test
    fun semesterUnassignedAndSort() {
        assertEquals(ParsedCourse("HPC Winter", null), SemesterParser.parse("HPC Winter"))
        assertTrue(SemesterParser.sortKey("27ss")!! > SemesterParser.sortKey("26ws")!!)
        assertTrue(SemesterParser.sortKey("27ws")!! > SemesterParser.sortKey("27ss")!!)
        assertNull(SemesterParser.sortKey("Unassigned"))
    }

    @Test
    fun onlyExactFoldersAreCategorized() {
        assertEquals(DocType.LECTURE, DocType.fromFolderName("Lecture"))
        assertEquals(DocType.LECTURE, DocType.fromFolderName("lecture"))
        assertEquals(DocType.EXERCISE, DocType.fromFolderName("Exercise"))
        assertEquals(DocType.EXAM, DocType.fromFolderName("Exam"))
        assertEquals(DocType.OTHERS, DocType.fromFolderName("Others"))
        assertNull(DocType.fromFolderName("Lecture Slides"))
        assertNull(DocType.fromFolderName("Excercise"))
        assertNull(DocType.fromFolderName("Homework"))
        assertNull(DocType.fromFolderName("Materials"))
    }

    @Test
    fun filtersAreAndAcrossFacetsAndOrWithinQueryTokens() {
        val lecture = entry("lecture.pdf", type = DocType.LECTURE)
        val exercise = entry("exercise.pdf", type = DocType.EXERCISE, folder = "Exercise")
        val otherCourse = entry("other.pdf", course = "PE", courseFolder = "PE-27ss", semester = "27ss", type = DocType.LECTURE)
        val entries = listOf(lecture, exercise, otherCourse)

        assertEquals(2, entries.count { it.matchesFilters(LibraryFilters(type = DocType.LECTURE)) })
        assertEquals(1, entries.count { it.matchesFilters(LibraryFilters(course = "HPC", type = DocType.EXERCISE)) })
        assertEquals(1, entries.count { it.matchesFilters(LibraryFilters(semester = SemesterKey.Named("27ss"))) })
        assertEquals(0, entries.count { it.matchesFilters(LibraryFilters(semester = SemesterKey.Unassigned)) })
        assertEquals(1, entries.count { it.matchesFilters(LibraryFilters(query = "hpc lecture")) })
        assertEquals(1, entries.count { it.matchesFilters(LibraryFilters(query = "hpc exercise")) })
        assertEquals(0, entries.count { it.matchesFilters(LibraryFilters(query = "pe exercise")) })
    }

    @Test
    fun treeKeepsSubfoldersAndUnknownOthersFolders() {
        val entries = listOf(
            entry("01.pdf", type = DocType.LECTURE, folder = "Lecture/Chapter 1"),
            entry("02.pdf", type = DocType.EXERCISE, folder = "Exercise"),
            entry("org.pdf", type = DocType.OTHERS, folder = "Organization"),
            entry("root.pdf", type = DocType.OTHERS, folder = ""),
        )

        val tree = buildLibraryTree(entries)
        assertEquals(1, tree.size)
        val course = tree.first()
        assertEquals(4, course.count)
        assertEquals(listOf(DocType.LECTURE, DocType.EXERCISE, DocType.OTHERS), course.categories.map { it.type })

        val lecture = course.categories[0]
        assertEquals("Chapter 1", lecture.folders.single().name)
        assertEquals("01.pdf", lecture.folders.single().files.single().name)

        val others = course.categories[2]
        assertEquals("Organization", others.folders.single().name)
        assertEquals("root.pdf", others.files.single().name)

        val rows = flattenLibraryTree(tree, emptySet())
        assertTrue(rows.any { it is LibraryRow.Folder && it.node.name == "Chapter 1" })
        assertTrue(rows.any { it is LibraryRow.File && it.entry.name == "root.pdf" })

        val collapsed = flattenLibraryTree(tree, setOf(course.key))
        assertEquals(1, collapsed.size)
        assertTrue(collapsed.single() is LibraryRow.Course)
    }

    @Test
    fun categoryCountsRollUp() {
        val entries = listOf(
            entry("a.pdf", type = DocType.LECTURE, folder = "Lecture/A"),
            entry("b.pdf", type = DocType.LECTURE, folder = "Lecture/A/B"),
            entry("c.pdf", type = DocType.LECTURE, folder = "Lecture/A/B"),
        )
        val course = buildLibraryTree(entries).single()
        val lecture = course.categories.single()
        assertEquals(3, lecture.count)
        val a = lecture.folders.single()
        assertEquals("A", a.name)
        assertEquals(3, a.count)
        assertEquals(2, a.folders.single().count)
    }

    @Test
    fun naturalSortHandlesNumbers() {
        assertTrue(naturalCompare("L2", "L10") < 0)
        assertTrue(naturalCompare("BSS_2", "BSS_10") < 0)
        assertTrue(naturalCompare("Course 2 26ws", "Course 10 26ws") < 0)
        assertEquals(0, naturalCompare("Lecture", "Lecture"))
        assertFalse(naturalCompare("Lecture", "Exercise") == 0)
    }

    @Test
    fun formatting() {
        assertEquals("1.0 MB", formatBytes(1024L * 1024L))
        assertEquals("1.5 MB", formatBytes((1.5 * 1024 * 1024).toLong()))
        assertEquals("512 KB", formatBytes(512L * 1024L))
        assertEquals("", formatBytes(0))
    }
}
