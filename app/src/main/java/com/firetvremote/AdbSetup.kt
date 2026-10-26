package com.firetvremote

import android.content.Context
import android.util.Base64
import dadb.AdbKeyPair
import dadb.Dadb
import java.math.BigInteger
import java.net.ConnectException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec

/**
 * Grants WRITE_SECURE_SETTINGS without a computer: the app connects to the Fire TV's
 * own ADB (127.0.0.1:5555, available while "ADB debugging" is on) and runs the
 * grant there. Fire OS asks once to allow the connection.
 */
object AdbSetup {
    private const val HOST = "127.0.0.1"
    private const val PORT = 5555

    sealed class Result {
        object Success : Result()
        object AdbOff : Result()
        data class Failed(val message: String) : Result()
    }

    /** Blocking; call off the main thread. */
    fun run(context: Context): Result = try {
        Dadb.create(HOST, PORT, keyPair(context), 5_000, 120_000).use { adb ->
            val response = adb.shell("pm grant ${context.packageName} ${ServiceKeeper.PERMISSION}")
            if (response.exitCode != 0) {
                Result.Failed(response.allOutput.trim().ifEmpty { "exit code ${response.exitCode}" })
            } else {
                ServiceKeeper.ensureEnabled(context)
                if (RemapService.isEnabled(context)) Result.Success
                else Result.Failed("Permission granted, but the service could not be switched on.")
            }
        }
    } catch (e: ConnectException) {
        Result.AdbOff
    } catch (e: Exception) {
        Result.Failed(e.message ?: e.javaClass.simpleName)
    }

    /**
     * Uses one saved key, so "Always allow" on the Fire TV prompt is remembered.
     * Built here rather than with dadb's helpers, which need java.util.Base64 (Android 8+).
     */
    private fun keyPair(context: Context): AdbKeyPair {
        val prefs = context.getSharedPreferences("adb", Context.MODE_PRIVATE)
        var privateKey = prefs.getString("private", null)
        var publicKey = prefs.getString("public", null)
        if (privateKey == null || publicKey == null) {
            val generated = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair()
            privateKey = Base64.encodeToString(generated.private.encoded, Base64.NO_WRAP)
            publicKey = Base64.encodeToString(adbPublicKey(generated.public as RSAPublicKey), Base64.NO_WRAP) +
                " remote-remap@firetv"
            prefs.edit().putString("private", privateKey).putString("public", publicKey).apply()
        }
        val key = KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(Base64.decode(privateKey, Base64.NO_WRAP)))
        return AdbKeyPair(key, publicKey!!.toByteArray() + 0)
    }

    /** ADB's RSA public key layout (AOSP RSAPublicKey struct), little-endian. */
    private fun adbPublicKey(key: RSAPublicKey): ByteArray {
        val words = 64
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n = key.modulus
        val rr = BigInteger.ONE.shiftLeft(words * 32 * 2).mod(n)
        val n0inv = r32.subtract(n.mod(r32).modInverse(r32))
        val buffer = ByteBuffer.allocate(4 + 4 + words * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(words)
        buffer.putInt(n0inv.toInt())
        var rest = n
        repeat(words) {
            buffer.putInt(rest.mod(r32).toInt())
            rest = rest.shiftRight(32)
        }
        rest = rr
        repeat(words) {
            buffer.putInt(rest.mod(r32).toInt())
            rest = rest.shiftRight(32)
        }
        buffer.putInt(key.publicExponent.toInt())
        return buffer.array()
    }
}
