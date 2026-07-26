package com.citation.core.capture

import com.citation.core.identity.IdentityKey
import com.citation.core.identity.IdentitySet
import com.citation.core.key.EntityKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturePromotionTest {

    private var seq = 0L
    private fun noteKey() = EntityKey("ER", "Note", ++seq)
    private val bookKey = EntityKey("ER", "Book", 42)

    private fun provisionalCluster(
        clusterId: String,
        title: String,
        boundBookKey: EntityKey? = null
    ) = CaptureClusterer.ProvisionalSource(
        clusterId = clusterId,
        rung = ProvenanceRung.ofClusterId(clusterId)!!,
        displayTitle = title,
        memberKeys = listOf(noteKey(), noteKey()),
        boundBookKey = boundBookKey
    )

    @Test
    fun promotesProvisionalBookIdentityClusterByHardMatch() {
        // Kindle highlights clustered under an ISBN before the book was held; the EPUB now arrives.
        val cluster = provisionalCluster(
            ClusterId.ofBookIdentity(IdentityKey.Isbn("9781449373320")),
            "Designing Data-Intensive Applications"
        )
        val record = CapturePromotion.PromotableRecord(
            bookKey = bookKey,
            identity = IdentitySet(IdentityKey.Isbn("978-1449373320")),
            title = "Designing Data-Intensive Applications",
            author = "Kleppmann"
        )
        val promotions = CapturePromotion.promote(record, listOf(cluster))
        assertEquals(1, promotions.size)
        assertEquals(bookKey, promotions.single().bookKey)
        assertEquals(cluster.memberKeys, promotions.single().memberKeys)
    }

    @Test
    fun promotesUrlClusterByFuzzyTitle() {
        // Article captures clustered by URL; you later add the book properly and the title matches.
        val cluster = provisionalCluster("url:https://x.example/ddia", "Designing Data Intensive Applications")
        val record = CapturePromotion.PromotableRecord(
            bookKey = bookKey,
            identity = IdentitySet(IdentityKey.Isbn("9781449373320")),
            title = "Designing Data-Intensive Applications",
            author = "Kleppmann"
        )
        assertEquals(1, CapturePromotion.promote(record, listOf(cluster)).size)
    }

    @Test
    fun differentEditionNeverPromotes() {
        // A 2nd-edition ISBN record must not claim a cluster keyed to the 1st edition's ISBN.
        val cluster = provisionalCluster(
            ClusterId.ofBookIdentity(IdentityKey.Isbn("9781449373320")),
            "Designing Data-Intensive Applications"
        )
        val secondEd = CapturePromotion.PromotableRecord(
            bookKey = bookKey,
            identity = IdentitySet(IdentityKey.Isbn("9781098119003")),
            title = "Designing Data-Intensive Applications",
            author = "Kleppmann"
        )
        assertTrue(CapturePromotion.promote(secondEd, listOf(cluster)).isEmpty())
    }

    @Test
    fun unrelatedTitleDoesNotPromote() {
        val cluster = provisionalCluster("url:https://x.example", "Some Blog Post")
        val record = CapturePromotion.PromotableRecord(
            bookKey = bookKey,
            identity = IdentitySet(IdentityKey.Isbn("9781449373320")),
            title = "An Entirely Different Book",
            author = null
        )
        assertTrue(CapturePromotion.promote(record, listOf(cluster)).isEmpty())
    }

    @Test
    fun alreadyBoundClustersAreSkipped() {
        val cluster = provisionalCluster(
            "url:https://x.example/ddia",
            "Designing Data-Intensive Applications",
            boundBookKey = EntityKey("ER", "Book", 9)
        )
        val record = CapturePromotion.PromotableRecord(
            bookKey = bookKey,
            identity = IdentitySet(IdentityKey.Isbn("9781449373320")),
            title = "Designing Data-Intensive Applications",
            author = "Kleppmann"
        )
        assertTrue(CapturePromotion.promote(record, listOf(cluster)).isEmpty())
    }
}
