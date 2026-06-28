package com.swaroop.excalidraw.plugin.persistence

/**
 * Thrown when an Excalidraw file cannot be parsed because its content is
 * corrupt or does not conform to the expected schema.
 *
 * Carries the [filePath] of the offending file so that callers and UI layers
 * can surface a meaningful error message without needing to re-read the
 * exception cause chain.
 *
 * This class has no dependency on IDE APIs or JCEF — it can be instantiated
 * and tested without a running IDE.
 *
 * @param filePath absolute or VFS path of the file that failed to parse.
 * @param cause    the underlying exception that triggered the parse failure.
 */
class ExcalidrawParseException(
    val filePath: String,
    cause: Throwable
) : Exception("Cannot parse Excalidraw file '$filePath': ${cause.message}", cause)
