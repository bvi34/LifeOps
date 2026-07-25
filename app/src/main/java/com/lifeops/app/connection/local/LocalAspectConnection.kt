package com.lifeops.app.connection.local

import com.lifeops.app.connection.ConnectionError
import com.lifeops.app.connection.ConnectionRegistry
import com.lifeops.app.connection.ConnectionResult
import com.lifeops.app.connection.service.AspectService

/**
 * The `local/aspect/*` and `local/category/*` routes.
 *
 *  - `/v1/LifeOps/local/aspect/create`   — params: name (required), color, icon. Find-or-create.
 *  - `/v1/LifeOps/local/aspect/rename`   — params: id (required), name (required).
 *  - `/v1/LifeOps/local/aspect/archive`  — params: id (required), archived (bool, default true).
 *  - `/v1/LifeOps/local/category/create` — params: aspectId (required), name (required).
 *  - `/v1/LifeOps/local/category/archive`— params: id (required), archived (bool, default true).
 */
object LocalAspectConnection {
    fun register(registry: ConnectionRegistry, aspectService: AspectService) {
        registry.register("local", "aspect", "create") { request ->
            val p = request.params
            val aspect = aspectService.createAspect(
                name = p.requireString("name"),
                color = p.getString("color"),
                icon = p.getString("icon")
            )
            ConnectionResult.ok("id" to aspect.id, "color" to aspect.color)
        }

        registry.register("local", "aspect", "rename") { request ->
            val p = request.params
            val id = p.requireString("id")
            aspectService.renameAspect(id, p.requireString("name"))
                ?.let { ConnectionResult.ok("id" to it.id) }
                ?: ConnectionResult.fail(ConnectionError.NOT_FOUND, "No aspect with id '$id'")
        }

        registry.register("local", "aspect", "archive") { request ->
            val p = request.params
            val id = p.requireString("id")
            val archived = if (p.has("archived")) p.getBoolean("archived") else true
            aspectService.setAspectArchived(id, archived)
            ConnectionResult.ok("id" to id, "archived" to archived)
        }

        registry.register("local", "category", "create") { request ->
            val p = request.params
            val category = aspectService.createCategory(
                aspectId = p.requireString("aspectId"),
                name = p.requireString("name")
            )
            ConnectionResult.ok("id" to category.id)
        }

        registry.register("local", "category", "archive") { request ->
            val p = request.params
            val id = p.requireString("id")
            val archived = if (p.has("archived")) p.getBoolean("archived") else true
            aspectService.setCategoryArchived(id, archived)
            ConnectionResult.ok("id" to id, "archived" to archived)
        }
    }
}
