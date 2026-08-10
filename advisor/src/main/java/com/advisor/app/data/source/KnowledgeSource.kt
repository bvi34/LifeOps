package com.advisor.app.data.source

import com.advisor.app.logic.KnowledgeDocument
import com.advisor.app.logic.SourceApp

/**
 * A read-only bridge from one hosted app's data into Advisor's retrieval corpus. Each source reads
 * its app's own database (same process) and flattens the rows into [KnowledgeDocument]s — this is
 * the seam that makes "RAG over all the other apps" real, mirroring how Logistics' `LifeOpsCatalog`
 * reads LifeOps' catalog. Sources never write; Advisor is a pure consumer.
 *
 * A source is only ever loaded when its app has been granted in
 * [com.advisor.app.logic.AdvisorPermissions], so the permission gate lives above this interface, not
 * inside it.
 */
interface KnowledgeSource {
    val source: SourceApp

    /** Snapshot this app's current data as documents. Called at query time, so it is always live. */
    suspend fun load(): List<KnowledgeDocument>
}
