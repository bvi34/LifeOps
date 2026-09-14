package com.maintenance.app.data.repository

import com.maintenance.app.data.db.dao.MaintenanceDao
import com.maintenance.app.data.db.entities.AssetAttributeEntity
import com.maintenance.app.data.db.entities.AssetEntity
import com.maintenance.app.data.model.Asset
import com.maintenance.app.logic.AssetAttributes
import com.maintenance.app.logic.AssetKind
import kotlinx.coroutines.flow.map

/**
 * The assets themselves: adding one, editing it, and the free-form attributes a household
 * decides are worth recording about it.
 */
class AssetStore(
    private val dao: MaintenanceDao
) {

    /**
     * Put a new asset on the register, with whatever of its kind's own fields were filled in.
     *
     * [attributes] arrives the way the form held it — every key the kind asks for, blanks included —
     * and the blanks are simply not written, which is the same rule [updateAsset] follows: an absent
     * row and an empty string must never both mean "no VIN".
     */
    suspend fun addAsset(
        name: String,
        kind: AssetKind,
        make: String? = null,
        model: String? = null,
        year: Int? = null,
        attributes: Map<String, String> = emptyMap(),
        colorArgb: Long = MaintenanceRepository.DEFAULT_COLOR
    ): String {
        val id = newId()
        val stamp = now()
        dao.upsertAsset(
            AssetEntity(
                id = id,
                name = name.trim(),
                kind = kind.key,
                make = make?.trim()?.takeIf { it.isNotBlank() },
                model = model?.trim()?.takeIf { it.isNotBlank() },
                year = year,
                purchasedAt = null,
                purchasePriceCents = null,
                currentValueCents = null,
                notes = null,
                colorArgb = colorArgb,
                archived = false,
                sortOrder = dao.nextAssetSortOrder(),
                createdAt = stamp,
                updatedAt = stamp
            )
        )
        val filled = attributes
            .mapNotNull { (key, value) -> kind.spec(key)?.let { it to value } }
            .mapNotNull { (spec, value) ->
                AssetAttributes.normalise(spec, value).takeIf { it.isNotBlank() }?.let { spec.key to it }
            }
        if (filled.isNotEmpty()) {
            dao.upsertAttributes(filled.map { (key, value) -> AssetAttributeEntity(id, key, value) })
        }
        return id
    }

    /**
     * Save the asset and its kind-specific fields in one go.
     *
     * [attributes] is the complete set the form held, blanks included: a key whose value came back
     * empty is *deleted* rather than stored as an empty string, so a cleared VIN reads as absent
     * everywhere instead of as a value that happens to be blank.
     */
    suspend fun updateAsset(asset: Asset, attributes: Map<String, String>) {
        val existing = dao.getAsset(asset.id) ?: return
        dao.upsertAsset(
            existing.copy(
                name = asset.name.trim(),
                kind = asset.kind.key,
                make = asset.make?.trim()?.takeIf { it.isNotBlank() },
                model = asset.model?.trim()?.takeIf { it.isNotBlank() },
                year = asset.year,
                purchasedAt = asset.purchasedAt,
                purchasePriceCents = asset.purchasePriceCents,
                currentValueCents = asset.currentValueCents,
                notes = asset.notes?.trim()?.takeIf { it.isNotBlank() },
                colorArgb = asset.colorArgb,
                archived = asset.archived,
                updatedAt = now()
            )
        )
        val (kept, cleared) = attributes.entries.partition { it.value.isNotBlank() }
        if (kept.isNotEmpty()) {
            dao.upsertAttributes(kept.map { AssetAttributeEntity(asset.id, it.key, it.value.trim()) })
        }
        if (cleared.isNotEmpty()) {
            dao.deleteAttributes(asset.id, cleared.map { it.key })
        }
    }

    /**
     * One kind-specific field, written on its own.
     *
     * The whole-asset [updateAsset] is what a dialog uses, because a dialog is holding every field.
     * This exists for the one-tap writes a *screen* makes — accepting the region the ZIP worked out,
     * where making somebody open the edit dialog to agree with an answer already on the page would
     * be the wrong shape of gesture. The clearing rule is the same one: a blank value deletes the
     * row rather than storing an empty string.
     */
    suspend fun setAttribute(assetId: String, key: String, value: String) {
        val asset = dao.getAsset(assetId) ?: return
        val trimmed = value.trim()
        if (trimmed.isBlank()) {
            dao.deleteAttributes(assetId, listOf(key))
        } else {
            dao.upsertAttributes(listOf(AssetAttributeEntity(assetId, key, trimmed)))
        }
        dao.upsertAsset(asset.copy(updatedAt = now()))
    }

    suspend fun setArchived(assetId: String, archived: Boolean) {
        val existing = dao.getAsset(assetId) ?: return
        dao.upsertAsset(existing.copy(archived = archived, updatedAt = now()))
    }

    /**
     * Delete an asset and everything under it. Returns the LifeOps tasks its plans had published,
     * which the caller takes off the week: the database cascade cannot reach into another app, and
     * "Truck: Oil change" outliving the truck by a year is exactly the kind of orphan that teaches
     * people to stop trusting a shared week.
     */
    suspend fun deleteAsset(assetId: String): List<String> {
        val published = dao.taskIdsForAsset(assetId)
        dao.deleteAsset(assetId)
        return published
    }
}
