package com.lightningcapsule.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Pure-local tests for the offline queue. No network, no Android framework —
 * just a temp folder standing in for `filesDir/capsule_queue/`.
 */
class CaptureQueueTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun queueDir() = File(tmp.root, "capsule_queue")

    private var seq = 0
    private fun sourceFile(bytes: Int = 16): File {
        val f = File(tmp.newFolder("src-${seq++}"), "rec.m4a")
        f.writeBytes(ByteArray(bytes) { 1 })
        return f
    }

    @Test
    fun enqueue_then_peek_returns_entry() {
        val q = CaptureQueue(queueDir())

        val result = q.enqueue(sourceFile(), createdAtMs = 100L)

        assertTrue(result is EnqueueResult.Enqueued)
        assertEquals(1, (result as EnqueueResult.Enqueued).pending)
        assertEquals(1, q.count())
        val head = q.peek()!!
        assertEquals(100L, head.createdAtMs)
        assertEquals(0, head.attempts)
        assertTrue(q.fileFor(head).exists())
    }

    @Test
    fun queue_is_fifo() {
        val q = CaptureQueue(queueDir())
        q.enqueue(sourceFile(), createdAtMs = 1L)
        q.enqueue(sourceFile(), createdAtMs = 2L)
        q.enqueue(sourceFile(), createdAtMs = 3L)

        assertEquals(listOf(1L, 2L, 3L), q.snapshot().map { it.createdAtMs })

        q.remove(q.peek()!!)
        assertEquals(listOf(2L, 3L), q.snapshot().map { it.createdAtMs })
    }

    @Test
    fun remove_deletes_backing_file_and_index_entry() {
        val q = CaptureQueue(queueDir())
        q.enqueue(sourceFile())
        val head = q.peek()!!
        val backing = q.fileFor(head)
        assertTrue(backing.exists())

        q.remove(head)

        assertFalse(backing.exists())
        assertEquals(0, q.count())
        assertNull(q.peek())
    }

    @Test
    fun increment_attempts_persists_across_reload() {
        val dir = queueDir()
        val q = CaptureQueue(dir)
        q.enqueue(sourceFile())
        q.incrementAttempts(q.peek()!!)
        q.incrementAttempts(q.peek()!!)
        assertEquals(2, q.peek()!!.attempts)

        val reopened = CaptureQueue(dir)
        assertEquals(1, reopened.count())
        assertEquals(2, reopened.peek()!!.attempts)
    }

    @Test
    fun rejects_new_capsule_when_count_cap_reached() {
        val q = CaptureQueue(queueDir(), maxCount = 2, maxBytes = 1_000_000)

        assertTrue(q.enqueue(sourceFile()) is EnqueueResult.Enqueued)
        assertTrue(q.enqueue(sourceFile()) is EnqueueResult.Enqueued)
        assertTrue(q.enqueue(sourceFile()) is EnqueueResult.QueueFull)
        assertEquals(2, q.count())
    }

    @Test
    fun rejects_new_capsule_when_byte_cap_reached() {
        val q = CaptureQueue(queueDir(), maxCount = 50, maxBytes = 100L)

        assertTrue(q.enqueue(sourceFile(bytes = 60)) is EnqueueResult.Enqueued)
        assertTrue(q.enqueue(sourceFile(bytes = 60)) is EnqueueResult.QueueFull)
        assertEquals(1, q.count())
    }

    @Test
    fun corrupt_index_is_tolerated() {
        val dir = queueDir()
        dir.mkdirs()
        File(dir, "index.json").writeText("{ not json at all ][ }")

        val q = CaptureQueue(dir)

        assertEquals(0, q.count())
        // Still usable afterwards.
        assertTrue(q.enqueue(sourceFile()) is EnqueueResult.Enqueued)
        assertEquals(1, q.count())
    }

    @Test
    fun index_entry_pointing_at_missing_file_is_skipped() {
        val dir = queueDir()
        dir.mkdirs()
        File(dir, "real.m4a").writeBytes(ByteArray(8) { 1 })
        File(dir, "index.json").writeText(
            """
            [
              {"file":"real.m4a","createdAtMs":1,"attempts":0},
              {"file":"ghost.m4a","createdAtMs":2,"attempts":0}
            ]
            """.trimIndent(),
        )

        val q = CaptureQueue(dir)

        assertEquals(1, q.count())
        assertEquals("real.m4a", q.peek()!!.file)
    }

    @Test
    fun malformed_index_entries_are_skipped_not_fatal() {
        val dir = queueDir()
        dir.mkdirs()
        File(dir, "real.m4a").writeBytes(ByteArray(8) { 1 })
        File(dir, "index.json").writeText(
            """[42, "nope", {"createdAtMs":5}, {"file":"real.m4a","createdAtMs":7,"attempts":3}]""",
        )

        val q = CaptureQueue(dir)

        assertEquals(1, q.count())
        assertEquals(3, q.peek()!!.attempts)
    }

    @Test
    fun peek_skips_and_prunes_zero_byte_files() {
        val dir = queueDir()
        val q = CaptureQueue(dir)
        q.enqueue(sourceFile(), createdAtMs = 1L)
        q.enqueue(sourceFile(), createdAtMs = 2L)

        // Simulate the head file being truncated/corrupted on disk.
        q.fileFor(q.snapshot().first()).writeBytes(ByteArray(0))

        val head = q.peek()!!
        assertEquals(2L, head.createdAtMs)
        assertEquals(1, q.count())
    }
}
