package com.dragonfly.install

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Sha256Test {

    @Test
    fun `matches the known sha-256 test vector`() {
        // NIST vector: SHA-256("abc")
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256.of("abc".byteInputStream()),
        )
    }

    @Test
    fun `file hashing and case-insensitive compare`() {
        val file = File.createTempFile("sha", ".bin").apply {
            deleteOnExit()
            writeText("abc")
        }
        assertTrue(Sha256.matches(file, "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD"))
        assertTrue(Sha256.matches(file, " ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad "))
        assertFalse(Sha256.matches(file, "deadbeef"))
    }

    @Test
    fun `empty input hashes to the empty-string digest`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Sha256.of(ByteArray(0).inputStream()),
        )
    }
}
