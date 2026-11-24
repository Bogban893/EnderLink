package org.enderlink.network

import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.AccessDeniedException
import org.enderlink.network.connection.Endpoint
import org.enderlink.network.tcp.TcpConnection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TransferFailureClassifierTest {
    @Test
    fun `closed local TCP port is reported as a failed connection`() {
        val port = ServerSocket(0).use { it.localPort }
        val failure = assertFailsWith<ConnectException> {
            TcpConnection.connect(Endpoint("127.0.0.1", port), connectTimeoutMs = 500)
        }
        assertEquals(TransferFailureKind.CONNECTION_REFUSED, TransferFailureClassifier.classify(failure))
    }

    @Test
    fun `classifies only observed failures and never infers offline presence`() {
        val cases = listOf(
            ConnectException("refused") to TransferFailureKind.CONNECTION_REFUSED,
            SocketTimeoutException("timeout") to TransferFailureKind.NO_RESPONSE,
            UnknownHostException("unknown") to TransferFailureKind.UNKNOWN_ADDRESS,
            IncompatibleTransferProtocolException("ELW4") to TransferFailureKind.INCOMPATIBLE_PROTOCOL,
            EOFException("closed") to TransferFailureKind.CONNECTION_LOST,
            AccessDeniedException("world") to TransferFailureKind.LOCAL_STORAGE,
            IOException("unknown") to TransferFailureKind.UNKNOWN,
        )
        for ((failure, expected) in cases) {
            assertEquals(expected, TransferFailureClassifier.classify(IOException("outer", failure)))
        }
        assertEquals(TransferFailureKind.LOCAL_STORAGE,
            TransferFailureClassifier.classify(RemoteTransferException("recipient", TransferFailureKind.LOCAL_STORAGE)))
        assertEquals(TransferFailureKind.REMOTE_FAILURE,
            TransferFailureClassifier.classify(RemoteTransferException("recipient", null)))
    }
}
