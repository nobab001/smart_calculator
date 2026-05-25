package com.example.smartcalculator

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import android.util.Log
import java.security.MessageDigest
import kotlin.system.exitProcess

/**
 * Runtime integrity guard.
 *
 * Protects the app against:
 *   1. Package-name rebrand  – verifies the process package name.
 *   2. APK re-signing        – compares the certificate SHA-256 to the expected value.
 *   3. Debugger attachment   – kills the process if a debugger is connected.
 *
 * HOW TO SET UP THE SIGNATURE HASH (one-time step):
 *   a) Build and sign your release APK normally.
 *   b) Install it and run it once with EXPECTED_SIG = "" (the check is skipped).
 *   c) In logcat filter by tag "AppGuard" – you will see the actual SHA-256 printed.
 *   d) Copy that hex string into EXPECTED_SIG below (split across the three parts
 *      to make it harder to spot in decompiled bytecode).
 *   e) Rebuild and publish. From this point any re-signed APK will silently quit.
 */
object AppGuard {

    private const val EXPECTED_PKG = "com.example.smartcalculator"

    // Split the expected SHA-256 across three parts so it is not a single
    // searchable string in the decompiled DEX.  Fill in after first release build.
    // Example split of "aabbcc...ff":  "aabb" + "cc...f" + "f"
    private val S1 = ""   // ← first  ~21 hex chars of SHA-256
    private val S2 = ""   // ← middle ~21 hex chars
    private val S3 = ""   // ← last   ~22 hex chars

    // ── public entry point ────────────────────────────────────────────────────

    fun check(ctx: Context) {
        guardPackage(ctx)
        guardDebugger()
        guardSignature(ctx)
    }

    // ── checks ────────────────────────────────────────────────────────────────

    private fun guardPackage(ctx: Context) {
        if (ctx.packageName != EXPECTED_PKG) die()
    }

    private fun guardDebugger() {
        if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) die()
    }

    private fun guardSignature(ctx: Context) {
        val expected = S1 + S2 + S3
        val actual   = certSha256(ctx) ?: run { die(); return }

        // Always log the actual hash (stripped in release by ProGuard assumenosideeffects)
        Log.d("AppGuard", "cert=$actual")

        if (expected.isNotEmpty() && !expected.equals(actual, ignoreCase = true)) die()
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun certSha256(ctx: Context): String? = runCatching {
        val pm   = ctx.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES)
        }
        val bytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.firstOrNull()?.toByteArray()
        } ?: return@runCatching null
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /** Silently terminate the process – no error dialog, no crash report. */
    private fun die(): Nothing = exitProcess(0)
}
