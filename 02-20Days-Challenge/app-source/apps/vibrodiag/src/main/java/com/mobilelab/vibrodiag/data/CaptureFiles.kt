package com.mobilelab.vibrodiag.data

import java.io.File
import java.io.IOException
import java.nio.file.Files

/** Only finished file bundles are published to a numbered group directory. */
internal object CaptureFiles {
    fun stage(parent: File, destination: SamplePaths): SamplePaths {
        parent.mkdirs()
        return SamplePaths.of(Files.createTempDirectory(parent.toPath(), "capture-").toFile(), destination.stem)
    }

    fun publish(stage: SamplePaths, destination: SamplePaths, move: (File, File) -> Boolean = File::renameTo) {
        require(stage.stem == destination.stem)
        check(listOf(stage.audio, stage.accel, stage.features, stage.meta).all { it.isFile } && stage.audio.length() > 44) {
            "录音文件不完整，不能替换原组"
        }
        val target = destination.dir
        val backup = backupFor(target)
        target.parentFile?.mkdirs()
        recover(destination, move)
        if (target.exists() && !move(target, backup)) throw IOException("无法备份原录音：" + target.name)
        if (!move(stage.dir, target)) {
            recover(destination, move)
            throw IOException("无法提交新录音：" + target.name)
        }
        // If cleanup fails, the fully committed target still wins on next startup.
        backup.deleteRecursively()
    }

    fun recover(destination: SamplePaths, move: (File, File) -> Boolean = File::renameTo) {
        val target = destination.dir
        val backup = backupFor(target)
        if (!backup.exists()) return
        if (target.exists()) {
            if (!backup.deleteRecursively()) throw IOException("无法清理旧录音备份：" + backup.name)
        } else if (!move(backup, target)) {
            throw IOException("无法还原原录音：" + target.name)
        }
    }

    internal fun backupFor(target: File): File = File(target.parentFile, ".previous-" + target.name)
}
