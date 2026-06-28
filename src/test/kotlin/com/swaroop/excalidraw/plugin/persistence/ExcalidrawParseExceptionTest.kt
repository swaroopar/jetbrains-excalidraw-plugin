package com.swaroop.excalidraw.plugin.persistence

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue

class ExcalidrawParseExceptionTest {

    @Test
    fun `constructor sets message containing filePath`() {
        val filePath = "/project/diagram.excalidraw"
        val cause = RuntimeException("unexpected end of JSON")

        val exception = ExcalidrawParseException(filePath, cause)

        assertNotNull(exception.message, "message must not be null")
        assertTrue(
            exception.message!!.contains(filePath),
            "message '${exception.message}' must contain filePath '$filePath'"
        )
    }

    @Test
    fun `constructor sets cause correctly`() {
        val filePath = "/project/diagram.excalidraw"
        val cause = IllegalArgumentException("missing required field: elements")

        val exception = ExcalidrawParseException(filePath, cause)

        assertEquals(cause, exception.cause, "cause must be the Throwable passed to the constructor")
    }

    @Test
    fun `message is not empty`() {
        val exception = ExcalidrawParseException("/some/file.excalidraw", RuntimeException("err"))

        assertNotNull(exception.message)
        assertTrue(exception.message!!.isNotEmpty(), "message must not be empty")
    }
}
