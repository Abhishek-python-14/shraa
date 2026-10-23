package com.abhirha.shraa.apps

import android.content.Context

/**
 * Which installed apps are "connected" to SHRAA. Only connected apps can be opened, played in,
 * media-controlled or UI-controlled by voice.
 *
 * Default = everything connected (so existing behaviour does not change). The first time the user
 * switches a single app off, the list becomes explicit.
 */
class ConnectedAppsStore(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("shraa_connected_apps", Context.MODE_PRIVATE)

    var connectAll: Boolean
        get() = p.getBoolean("all", true)
        private set(v) { p.edit().putBoolean("all", v).apply() }

    private fun explicit(): MutableSet<String> = HashSet(p.getStringSet("pkgs", emptySet()) ?: emptySet())

    fun isConnected(pkg: String): Boolean = connectAll || pkg in explicit()

    fun connectedCount(allPackages: Collection<String>): Int =
        if (connectAll) allPackages.size else explicit().count { it in allPackages }

    /** Connect / disconnect one app. */
    fun set(pkg: String, on: Boolean, allPackages: Collection<String>) =
        setMany(listOf(pkg), on, allPackages)

    /** Connect / disconnect several apps at once (e.g. everything in the "Music" filter). */
    fun setMany(pkgs: Collection<String>, on: Boolean, allPackages: Collection<String>) {
        val cur = if (connectAll) HashSet(allPackages) else explicit()
        if (on) cur.addAll(pkgs) else cur.removeAll(pkgs.toSet())
        p.edit().putBoolean("all", false).putStringSet("pkgs", cur).apply()
    }

    fun connectEverything() {
        p.edit().putBoolean("all", true).putStringSet("pkgs", emptySet()).apply()
    }

    fun disconnectEverything() {
        p.edit().putBoolean("all", false).putStringSet("pkgs", emptySet()).apply()
    }
}
