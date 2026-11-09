
package com.efemoto.motodiagpro

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

class ViecarConnection(
    private val host: String = "192.168.0.10",
    private val port: Int = 35000,
    private val socketFactory: (() -> Socket)? = null
) : AutoCloseable {

    private var socket: Socket? = null
    private var input: BufferedInputStream? = null
    private var output: BufferedOutputStream? = null

    @Synchronized
    fun connect(timeoutMs: Int = 8000) {
        if (isConnected()) return

        disconnect()

        val newSocket = socketFactory?.invoke() ?: Socket()

        try {
            newSocket.tcpNoDelay = true
            newSocket.keepAlive = true
            newSocket.connect(
                InetSocketAddress(host, port),
                timeoutMs
            )
            newSocket.soTimeout = 5000

            socket = newSocket
            input = BufferedInputStream(newSocket.getInputStream())
            output = BufferedOutputStream(newSocket.getOutputStream())

        } catch (e: Exception) {
            try {
                newSocket.close()
            } catch (_: Exception) {
            }

            disconnect()

            throw IOException(
                "Viecar bağlantısı kurulamadı: ${e.message}",
                e
            )
        }
    }

    @Synchronized
    fun isConnected(): Boolean {
        val current = socket

        return current != null &&
                current.isConnected &&
                !current.isClosed &&
                !current.isInputShutdown &&
                !current.isOutputShutdown
    }

    @Synchronized
    fun sendCommand(
        command: String,
        timeoutMs: Int = 5000
    ): String {

        if (!isConnected()) {
            throw IOException("Önce Viecar bağlantısı kurulmalı.")
        }

        val currentSocket = socket
            ?: throw IOException("Socket bulunamadı.")

        val reader = input
            ?: throw IOException("Giriş kanalı bulunamadı.")

        val writer = output
            ?: throw IOException("Çıkış kanalı bulunamadı.")

        try {
            currentSocket.soTimeout = timeoutMs

            // Önceki komuttan kalmış verileri temizle.
            while (reader.available() > 0) {
                if (reader.read() == -1) break
            }

            writer.write(command.trim().toByteArray(Charsets.US_ASCII))
            writer.write('\r'.code)
            writer.flush()

            val response = StringBuilder()

            while (true) {
                val value = reader.read()

                if (value == -1) {
                    throw IOException("Adaptör bağlantıyı kapattı.")
                }

                if (value == '>'.code) {
                    break
                }

                response.append(value.toChar())

                if (response.length > 8192) {
                    throw IOException("Adaptör yanıtı beklenenden uzun.")
                }
            }

            return response.toString()
                .replace("\r", "\n")
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString("\n")

        } catch (e: Exception) {
            if (e is IOException) throw e

            throw IOException(
                "Komut gönderme hatası: ${e.message}",
                e
            )
        }
    }

    @Synchronized
    fun disconnect() {
        try {
            input?.close()
        } catch (_: Exception) {
        }

        try {
            output?.close()
        } catch (_: Exception) {
        }

        try {
            socket?.close()
        } catch (_: Exception) {
        }

        input = null
        output = null
        socket = null
    }

    override fun close() {
        disconnect()
    }
}
