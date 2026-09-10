package com.lightningcapsule.wear

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One queued, not-yet-uploaded capsule. [file] is a bare name inside the queue dir. */
data class QueuedCapsule(
    val file: String,
    val createdAtMs: Long,
    val attempts: Int,
)

sealed interface EnqueueResult {
    /** Stored on disk. [pending] is the new queue size. */
    data class Enqueued(val pending: Int) : EnqueueResult

    /** Queue is at its count or byte cap; the capsule was dropped. */
    data object QueueFull : EnqueueResult
}

/**
 * A tiny FIFO on-disk queue for capsules that failed to upload.
 *
 * Files live directly in [dir]; metadata is a single JSON array in `index.json`
 * (`[{file, createdAtMs, attempts}, ...]`). No database, no Android
 * dependencies — safe to unit-test against a plain temp folder.
 *
 * Every public method is `synchronized(lock)`. Reads are served from an
 * in-memory copy of the index; mutations flush to disk before returning. A
 * corrupt index or an entry that points at a missing/empty file is skipped, not
 * fatal.
 */
class CaptureQueue(
    private val dir: File,
    private val maxCount: Int = MAX_COUNT,
    private val maxBytes: Long = MAX_BYTES,
) {

    private val lock = Any()
    private val indexFile = File(dir, INDEX_NAME)
    private val entries = mutableListOf<QueuedCapsule>()

    init {
        synchronized(lock) {
            dir.mkdirs()
            load()
        }
    }

    val isEmpty: Boolean get() = synchronized(lock) { entries.isEmpty() }

    fun count(): Int = synchronized(lock) { entries.size }

    /** Total size, in bytes, of the backing files that still exist. */
    fun sizeBytes(): Long = synchronized(lock) { currentBytes() }

    fun snapshot(): List<QueuedCapsule> = synchronized(lock) { entries.toList() }

    fun fileFor(entry: QueuedCapsule): File = File(dir, entry.file)

    /**
     * Copies [source] into the queue and records it. The caller still owns
     * [source] and should delete it afterwards.
     */
    fun enqueue(
        source: File,
        createdAtMs: Long = System.currentTimeMillis(),
    ): EnqueueResult = synchronized(lock) {
        if (pruneDead()) persist()

        val sourceLen = source.length()
        if (entries.size >= maxCount) return@synchronized EnqueueResult.QueueFull
        if (currentBytes() + sourceLen > maxBytes) return@synchronized EnqueueResult.QueueFull

        val name = "capsule-$createdAtMs-${System.nanoTime()}.m4a"
        source.copyTo(File(dir, name), overwrite = true)
        entries.add(QueuedCapsule(name, createdAtMs, 0))
        persist()
        EnqueueResult.Enqueued(entries.size)
    }

    /** Head of the queue whose backing file still exists, or null. Prunes dead entries. */
    fun peek(): QueuedCapsule? = synchronized(lock) {
        if (pruneDead()) persist()
        entries.firstOrNull()
    }

    /** Drops [entry] from the index and deletes its file. */
    fun remove(entry: QueuedCapsule) = synchronized(lock) {
        entries.removeAll { it.file == entry.file }
        runCatching { File(dir, entry.file).delete() }
        persist()
    }

    /** Bumps the retry counter for [entry] and persists. */
    fun incrementAttempts(entry: QueuedCapsule) = synchronized(lock) {
        val i = entries.indexOfFirst { it.file == entry.file }
        if (i >= 0) entries[i] = entries[i].copy(attempts = entries[i].attempts + 1)
        persist()
    }

    private fun currentBytes(): Long = entries.sumOf { File(dir, it.file).length() }

    /** Removes entries whose backing file is gone or empty. Returns true if anything changed. */
    private fun pruneDead(): Boolean {
        val before = entries.size
        entries.retainAll {
            val f = File(dir, it.file)
            f.exists() && f.length() > 0L
        }
        return entries.size != before
    }

    private fun load() {
        entries.clear()
        val text = runCatching { indexFile.readText() }.getOrNull() ?: return
        val arr = runCatching { JSONArray(text) }.getOrNull()
        if (arr == null) {
            // Corrupt index — start clean, but keep the bad file for forensics.
            runCatching { indexFile.renameTo(File(dir, "$INDEX_NAME.corrupt")) }
            return
        }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("file", "").ifBlank { null } ?: continue
            entries.add(
                QueuedCapsule(
                    file = name,
                    createdAtMs = o.optLong("createdAtMs", 0L),
                    attempts = o.optInt("attempts", 0),
                ),
            )
        }
        pruneDead()
    }

    private fun persist() {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(
                JSONObject()
                    .put("file", e.file)
                    .put("createdAtMs", e.createdAtMs)
                    .put("attempts", e.attempts),
            )
        }
        val payload = arr.toString()
        val tmp = File(dir, "$INDEX_NAME.tmp")
        runCatching {
            tmp.writeText(payload)
            if (!tmp.renameTo(indexFile)) {
                indexFile.writeText(payload)
                tmp.delete()
            }
        }
    }

    companion object {
        /** Hard caps: 50 capsules or 30 MB, whichever comes first. */
        const val MAX_COUNT = 50
        const val MAX_BYTES = 30L * 1024 * 1024
        private const val INDEX_NAME = "index.json"
    }
}
