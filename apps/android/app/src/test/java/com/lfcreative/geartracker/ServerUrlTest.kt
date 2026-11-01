package com.lfcreative.geartracker

import com.lfcreative.geartracker.core.ServerUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUrlTest {
    private fun ok(input: String, cleartext: Boolean = false) = ServerUrl.normalize(input, cleartext).getOrThrow()
    private fun err(input: String, cleartext: Boolean = false) =
        ServerUrl.normalize(input, cleartext).exceptionOrNull()?.message

    @Test fun `accepts https and normalises case and trailing slash`() {
        assertEquals("https://api.example.com", ok("  HTTPS://API.Example.com/ "))
        assertEquals("https://api.example.com:8443", ok("https://api.example.com:8443"))
    }

    @Test fun `http only when cleartext is allowed`() {
        assertEquals("http://10.0.2.2:54321", ok("http://10.0.2.2:54321", cleartext = true))
        assertTrue(err("http://10.0.2.2:54321")!!.contains("https"))
    }

    @Test fun `rejects blank, wrong scheme, missing host`() {
        assertEquals("Enter the server URL", err("   "))
        assertTrue(err("ftp://host")!!.contains("https://"))
        assertTrue(err("https://")!!.isNotEmpty())
        assertTrue(err("not a url")!!.isNotEmpty())
    }

    @Test fun `rejects credentials, paths, query and fragment`() {
        assertTrue(err("https://user:pw@host.example")!!.contains("credentials"))
        assertTrue(err("https://host.example/rest/v1")!!.contains("path"))
        assertTrue(err("https://host.example/?a=1")!!.contains("query"))
        assertTrue(err("https://host.example/#x")!!.contains("query"))
    }
}
