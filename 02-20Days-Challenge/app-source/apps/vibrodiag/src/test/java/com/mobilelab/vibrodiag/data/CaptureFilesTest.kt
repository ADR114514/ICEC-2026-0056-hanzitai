package com.mobilelab.vibrodiag.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class CaptureFilesTest {
    @get:Rule val folder = TemporaryFolder()

    private fun paths(dir: File) = SamplePaths.of(dir, "g01_C1_NORMAL_LOW")

    private fun fill(paths: SamplePaths, value: String) {
        paths.dir.mkdirs()
        paths.audio.writeText(value.repeat(50))
        paths.accel.writeText(value)
        paths.features.writeText(value)
        paths.meta.writeText(value)
    }

    @Test
    fun discardOrFailBeforePublishKeepsOriginalBundle() {
        val original = paths(File(folder.root, "g01"))
        fill(original, "old")
        val temp = CaptureFiles.stage(File(folder.root, "staging"), original)
        temp.audio.writeText("new".repeat(50))
        assertTrue(runCatching { CaptureFiles.publish(temp, original) }.isFailure)
        assertEquals("old".repeat(50), original.audio.readText())
        assertEquals("old", original.features.readText())
        assertTrue(temp.audio.isFile)
    }

    @Test
    fun failedRenameRestoresPreviousGroupAndSuccessReplacesWholeBundle() {
        val original = paths(File(folder.root, "g01"))
        fill(original, "old")
        val temp = CaptureFiles.stage(File(folder.root, "staging"), original)
        fill(temp, "new")
        val result = runCatching {
            CaptureFiles.publish(temp, original) { from, to -> from != temp.dir && from.renameTo(to) }
        }
        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals("old", original.meta.readText())
        assertEquals("new", temp.features.readText())
        CaptureFiles.publish(temp, original)
        assertEquals("new".repeat(50), original.audio.readText())
        assertEquals("new", original.accel.readText())
        assertEquals("new", original.features.readText())
        assertEquals("new", original.meta.readText())
        assertFalse(temp.dir.exists())
    }

    @Test
    fun startupRestoresPriorBundleIfProcessStoppedDuringReplacement() {
        val original = paths(File(folder.root, "g01"))
        fill(original, "old")
        val backup = CaptureFiles.backupFor(original.dir)
        assertTrue(original.dir.renameTo(backup))
        CaptureFiles.recover(original)
        assertEquals("old", original.accel.readText())
        assertFalse(backup.exists())
    }
}
