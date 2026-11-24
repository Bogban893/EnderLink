package org.enderlink.network

import kotlinx.coroutines.TimeoutCancellationException
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.FileSystemException

/** Only conclusions supported by the observed exception; this does not infer peer presence. */
enum class TransferFailureKind {
    CONNECTION_REFUSED, NO_RESPONSE, UNKNOWN_ADDRESS, INCOMPATIBLE_PROTOCOL,
    CONNECTION_LOST, LOCAL_STORAGE, REMOTE_FAILURE, UNKNOWN,
}

class IncompatibleTransferProtocolException(message: String) : IOException(message)

class TransferRejectedException : IOException("Получатель отклонил входящую версию мира")

class RemoteTransferException(message: String, val remoteKind: TransferFailureKind?) : IOException(message)

object TransferFailureClassifier {
    fun classify(error: Throwable): TransferFailureKind {
        if (error is RemoteTransferException) return error.remoteKind?.takeUnless { it == TransferFailureKind.UNKNOWN }
            ?: TransferFailureKind.REMOTE_FAILURE
        var cause: Throwable? = error
        val seen = mutableSetOf<Throwable>()
        while (cause != null && seen.add(cause)) {
            when (cause) {
                is TransferRejectedException -> return TransferFailureKind.REMOTE_FAILURE
                is IncompatibleTransferProtocolException -> return TransferFailureKind.INCOMPATIBLE_PROTOCOL
                is FileSystemException -> return TransferFailureKind.LOCAL_STORAGE
                is UnknownHostException -> return TransferFailureKind.UNKNOWN_ADDRESS
                is ConnectException -> return TransferFailureKind.CONNECTION_REFUSED
                is SocketTimeoutException -> return TransferFailureKind.NO_RESPONSE
                is TimeoutCancellationException -> return TransferFailureKind.NO_RESPONSE
                is EOFException, is SocketException -> return TransferFailureKind.CONNECTION_LOST
            }
            cause = cause.cause
        }
        return TransferFailureKind.UNKNOWN
    }
}
