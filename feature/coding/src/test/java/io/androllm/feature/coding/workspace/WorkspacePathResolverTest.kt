package io.androllm.feature.coding.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** SAF tree URI → real filesystem path resolution. */
class WorkspacePathResolverTest {

    private val root = "/storage/emulated/0"

    @Test
    fun `primary tree uri resolves to a storage path`() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FMyProj"
        assertEquals("$root/Documents/MyProj", WorkspacePathResolver.resolveTreeUri(uri, root))
    }

    @Test
    fun `deeply nested folder resolves with all segments`() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3AProjects%2Fweb%2Fsite"
        assertEquals("$root/Projects/web/site", WorkspacePathResolver.resolveTreeUri(uri, root))
    }

    @Test
    fun `primary storage root resolves to the root itself`() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3A"
        assertEquals(root, WorkspacePathResolver.resolveTreeUri(uri, root))
    }

    @Test
    fun `raw doc id resolves to its path`() {
        val uri = "content://com.android.externalstorage.documents/tree/raw%3A%2Fstorage%2Femulated%2F0%2Fcode"
        assertEquals("$root/code", WorkspacePathResolver.resolveTreeUri(uri, root))
    }

    @Test
    fun `spaces and special characters survive decoding`() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3AMy%20Projects%2Fapp%20(one)"
        assertEquals("$root/My Projects/app (one)", WorkspacePathResolver.resolveTreeUri(uri, root))
    }

    @Test
    fun `non-primary volumes are rejected`() {
        val uri = "content://com.android.externalstorage.documents/tree/1234-5678%3Astuff"
        assertNull(WorkspacePathResolver.resolveTreeUri(uri, root))
    }

    @Test
    fun `cloud providers are rejected`() {
        val uri = "content://com.google.android.apps.docs.storage/tree/acc%3D1%3Bdoc%3D2"
        assertNull(WorkspacePathResolver.resolveTreeUri(uri, root))
    }

    @Test
    fun `non tree uris are rejected`() {
        assertNull(WorkspacePathResolver.resolveTreeUri("content://x.y/tree", root))
        assertNull(WorkspacePathResolver.resolveTreeUri("file:///sdcard/x", root))
        assertNull(WorkspacePathResolver.resolveTreeUri("not a uri", root))
    }

    @Test
    fun `display name is the last path segment`() {
        assertEquals("MyProj", WorkspacePathResolver.displayName("$root/Documents/MyProj"))
        assertEquals("MyProj", WorkspacePathResolver.displayName("$root/Documents/MyProj/"))
    }
}
