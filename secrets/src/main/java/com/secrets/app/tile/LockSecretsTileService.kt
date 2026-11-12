package com.secrets.app.tile

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.secrets.app.SecretsApp
import com.operations.vaultkit.VaultState

/**
 * Quick Settings tile that shuts the Secrets vault in one tap — for handing the phone over, or
 * leaving it on a desk, without opening the app to find its lock.
 *
 * It only ever locks. Unlocking needs the master password or biometrics inside the app, and a tile
 * that could open the vault from the shade (the lock screen included) would be a hole.
 */
class LockSecretsTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        val vault = SecretsApp.get(this).vault
        if (vault.state.value == VaultState.UNLOCKED) vault.lock()
        render()
    }

    private fun render() {
        val tile = qsTile ?: return
        tile.label = "Lock Secrets"
        when (SecretsApp.get(this).vault.state.value) {
            VaultState.UNLOCKED -> {
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = "Open · tap to lock"
            }
            VaultState.ABSENT -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.subtitle = "No vault yet"
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = "Locked"
            }
        }
        tile.updateTile()
    }
}
