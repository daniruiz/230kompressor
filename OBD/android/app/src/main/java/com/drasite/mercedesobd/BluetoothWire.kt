package com.drasite.mercedesobd

import android.bluetooth.BluetoothSocket
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BluetoothWire(private val socket: BluetoothSocket, private val log: Events) : Wire {
    private val input = socket.inputStream
    private val output = socket.outputStream
    private val timer = Executors.newSingleThreadScheduledExecutor()

    override fun exchange(command: String, timeoutMs: Long, timing: ExchangeTiming): String {
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
        var firstByteAt: Long? = null
        var lastPayloadByteAt: Long? = null
        // Timestamps describe host receive batches, never individual K-line bytes.
        // Payload and prompt received in the same batch have a zero prompt tail.
        fun timingFields(ended: Long, prompt: Boolean = false) = arrayOf(
            "host_gap_wait_ms" to timing.hostGapWaitMs,
            "total_seconds" to (ended - timing.totalStart) / 1000.0,
            "first_byte_ms" to firstByteAt?.let { it - started },
            "last_payload_byte_ms" to lastPayloadByteAt?.let { it - started },
            "prompt_tail_ms" to if (prompt) lastPayloadByteAt?.let { ended - it } else null,
            "timing_basis" to "host_receive_batches_not_kline",
        )
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
                val receivedAt = monoMs()
                if (got < 0) throw LinkTimeout(tr("Bluetooth desconectado", "Bluetooth disconnected"))
                if (got > 0) {
                    if (firstByteAt == null) firstByteAt = receivedAt
                    for (i in 0 until got) {
                        val b = chunk[i].u()
                        if (b == '>'.code) break
                        if (b != 13 && b != 10 && b != 9 && b != 32) lastPayloadByteAt = receivedAt
                    }
                }
                data.write(chunk, 0, got)
                val bytes = data.toByteArray()
                val prompt = bytes.indexOf('>'.code.toByte())
                if (prompt >= 0) {
                    if (bytes.copyOfRange(prompt + 1, bytes.size).any { it.u() > 32 })
                        throw LinkTimeout(tr("Tráfico inesperado después del prompt", "Unexpected traffic after the prompt"))
                    val raw = bytes.copyOfRange(0, prompt)
                    val reply = raw.toString(Charsets.US_ASCII)
                    log.event(
                        "exchange",
                        "command" to command,
                        "wire" to command,
                        "reply" to reply,
                        "raw_hex" to raw.hex(),
                        "prompt" to true,
                        "seconds" to (receivedAt - started) / 1000.0,
                        *timingFields(receivedAt, prompt = true),
                    )
                    return reply
                }
                if (data.size() > 65536) throw LinkTimeout(tr("Respuesta demasiado larga", "Response too long"))
            }
            throw LinkTimeout(tr("ELM sin prompt >: $command", "ELM missing prompt >: $command"))
        } catch (e: Exception) {
            val ended = monoMs()
            log.event(
                "exchange_error",
                "command" to command,
                "raw_hex" to data.toByteArray().hex(),
                "prompt" to false,
                "seconds" to (ended - started) / 1000.0,
                *timingFields(ended),
                "error" to e.toString(),
            )
            // A socket closed by the watchdog is also a lost prompt, not a
            // recoverable diagnostic response. Let the benchmark log it as fatal.
            if (e is IOException)
                throw LinkTimeout(tr("Bluetooth desconectado: ${e.message}", "Bluetooth disconnected: ${e.message}")).also { it.initCause(e) }
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
