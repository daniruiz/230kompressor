package com.drasite.mercedesobd

import android.bluetooth.BluetoothSocket
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BluetoothWire(private val socket: BluetoothSocket, private val log: Events) : Wire {
    private val input = socket.inputStream
    private val output = socket.outputStream
    private val timer = Executors.newSingleThreadScheduledExecutor()

    override fun exchange(command: String, timeoutMs: Long): String {
        val started = monoMs()
        val timeout =
            timer.schedule(
                {
                    try {
                        socket.close()
                    } catch (_: Exception) {}
                },
                timeoutMs,
                TimeUnit.MILLISECONDS,
            )
        val data = ByteArrayOutputStream()
        try {
            output.write((command + "\r").toByteArray(Charsets.US_ASCII))
            output.flush()
            while (monoMs() - started < timeoutMs) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val n = input.available()
                if (n == 0) {
                    Thread.sleep(3)
                    continue
                }
                val chunk = ByteArray(minOf(n, 4096))
                val got = input.read(chunk)
                if (got < 0) throw LinkTimeout("Bluetooth desconectado")
                data.write(chunk, 0, got)
                val bytes = data.toByteArray()
                val prompt = bytes.indexOf('>'.code.toByte())
                if (prompt >= 0) {
                    if (bytes.copyOfRange(prompt + 1, bytes.size).any { it.u() > 32 })
                        throw LinkTimeout("Tráfico inesperado después del prompt")
                    val raw = bytes.copyOfRange(0, prompt)
                    val reply = raw.toString(Charsets.US_ASCII)
                    log.event(
                        "exchange",
                        "command" to command,
                        "wire" to command,
                        "reply" to reply,
                        "raw_hex" to raw.hex(),
                        "prompt" to true,
                        "seconds" to (monoMs() - started) / 1000.0,
                    )
                    return reply
                }
                if (data.size() > 65536) throw LinkTimeout("Respuesta demasiado larga")
            }
            throw LinkTimeout("ELM sin prompt >: $command")
        } catch (e: Exception) {
            log.event(
                "exchange_error",
                "command" to command,
                "raw_hex" to data.toByteArray().hex(),
                "prompt" to false,
                "seconds" to (monoMs() - started) / 1000.0,
                "error" to e.toString(),
            )
            throw e
        } finally {
            timeout.cancel(false)
        }
    }

    override fun close() {
        timer.shutdownNow()
        socket.close()
    }
}
