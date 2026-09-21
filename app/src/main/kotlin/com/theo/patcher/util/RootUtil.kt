// util/RootUtil.kt — root shell execution and permission check
package com.theo.patcher.util

import java.io.OutputStreamWriter

object RootUtil {

    fun isRooted(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val output = process.inputStream.bufferedReader().readLine() ?: ""
            process.waitFor()
            output.contains("uid=0")
        } catch (e: Exception) { false }
    }

    fun exec(vararg commands: String): ShellResult {
        return try {
            val process = ProcessBuilder("su").redirectErrorStream(true).start()
            val writer = OutputStreamWriter(process.outputStream)
            for (cmd in commands) { writer.write(cmd + "\n") }
            writer.write("exit\n")
            writer.flush()
            writer.close()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            ShellResult(exitCode == 0, output, exitCode)
        } catch (e: Exception) {
            ShellResult(false, e.message ?: "error", -1)
        }
    }

    data class ShellResult(val success: Boolean, val output: String, val exitCode: Int)
}
