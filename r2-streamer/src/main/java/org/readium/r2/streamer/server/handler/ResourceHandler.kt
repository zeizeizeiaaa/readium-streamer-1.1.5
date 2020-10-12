/*
 * Module: r2-streamer-kotlin
 * Developers: Aferdita Muriqi, Clément Baumann
 *
 * Copyright (c) 2018. Readium Foundation. All rights reserved.
 * Use of this source code is governed by a BSD-style license which is detailed in the
 * LICENSE file present in the project repository where this source code is maintained.
 */

package org.readium.r2.streamer.server.handler

import org.nanohttpd.protocols.http.IHTTPSession
import org.nanohttpd.protocols.http.NanoHTTPD.MIME_PLAINTEXT
import org.nanohttpd.protocols.http.response.IStatus
import org.nanohttpd.protocols.http.response.Response
import org.nanohttpd.protocols.http.response.Response.newChunkedResponse
import org.nanohttpd.protocols.http.response.Response.newFixedLengthResponse
import org.nanohttpd.protocols.http.response.Status
import org.nanohttpd.router.RouterNanoHTTPD
import org.readium.r2.streamer.BuildConfig.DEBUG
import org.readium.r2.streamer.fetcher.Fetcher
import timber.log.Timber
import java.io.IOException
import java.io.InputStream


class ResourceHandler : RouterNanoHTTPD.DefaultHandler() {

    override fun getMimeType(): String? {
        return null
    }

    override fun getText(): String {
        return ResponseStatus.FAILURE_RESPONSE
    }

    override fun getStatus(): IStatus {
        return Status.OK
    }

    override fun get(uriResource: RouterNanoHTTPD.UriResource?, urlParams: Map<String, String>?,
                     session: IHTTPSession?): Response? {
        try {
            if (DEBUG) Timber.v("Method: ${session!!.method}, Uri: ${session.uri}")
            val fetcher = uriResource!!.initParameter(Fetcher::class.java)

            val filePath = getHref(session!!.uri)
            val link = fetcher.publication.linkWithHref(filePath)!!
            val mimeType = link.typeLink!!

            // If the content is of type html return the response this is done to
            // skip the check for following font deobfuscation check
            if (mimeType == "application/xhtml+xml") {
                return serveResponse(session, fetcher.dataStream(filePath), mimeType)
            }

            // ********************
            //  FONT DEOBFUSCATION
            // ********************

            return serveResponse(session, fetcher.dataStream(filePath), mimeType)
        } catch (e: Exception) {
            if (DEBUG) Timber.e(e)
            return newFixedLengthResponse(Status.INTERNAL_ERROR, mimeType, ResponseStatus.FAILURE_RESPONSE)
        }
    }

    private fun serveResponse(session: IHTTPSession, inputStream: InputStream, mimeType: String): Response {
        var response: Response?
        var rangeRequest: String? = session.headers["range"]

        try {
            // Calculate etag
            val etag = Integer.toHexString(inputStream.hashCode())

            // Support skipping:
            var startFrom: Long = 0
            var endAt: Long = -1
            if (rangeRequest != null) {
                if (rangeRequest.startsWith("bytes=")) {
                    rangeRequest = rangeRequest.substring("bytes=".length)
                    val minus = rangeRequest.indexOf('-')
                    try {
                        if (minus > 0) {
                            startFrom = java.lang.Long.parseLong(rangeRequest.substring(0, minus))
                            endAt = java.lang.Long.parseLong(rangeRequest.substring(minus + 1))
                        }
                    } catch (ignored: NumberFormatException) {
                    }

                }
            }

            // Change return code and add Content-Range header when skipping is requested
            val streamLength = inputStream.available().toLong()
            if (rangeRequest != null && startFrom >= 0) {
                if (startFrom >= streamLength) {
                    response = createResponse(Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "")
                    response.addHeader("Content-Range", "bytes 0-0/$streamLength")
                    response.addHeader("ETag", etag)
                } else {
                    if (endAt < 0) {
                        endAt = streamLength - 1
                    }
                    var newLen = endAt - startFrom + 1
                    if (newLen < 0) {
                        newLen = 0
                    }

                    val dataLen = newLen
                    inputStream.skip(startFrom)

                    response = createResponse(Status.PARTIAL_CONTENT, mimeType, inputStream)
                    response.addHeader("Content-Length", "" + dataLen)
                    response.addHeader("Content-Range", "bytes $startFrom-$endAt/$streamLength")
                    response.addHeader("ETag", etag)
                }
            } else {
                if (etag == session.headers["if-none-match"])
                    response = createResponse(Status.NOT_MODIFIED, mimeType, "")
                else {
                    response = createResponse(Status.OK, mimeType, inputStream)
                    response.addHeader("Content-Length", "" + streamLength)
                    response.addHeader("ETag", etag)
                }
            }
        } catch (ioe: IOException) {
            response = getResponse("Forbidden: Reading file failed")
        } catch (ioe: NullPointerException) {
            response = getResponse("Forbidden: Reading file failed")
        }

        return response ?: getResponse("Error 404: File not found")
    }

    private fun createResponse(status: Status, mimeType: String, message: InputStream): Response {
        val response = newChunkedResponse(status, mimeType, message)
        response.addHeader("Accept-Ranges", "bytes")
        return response
    }

    private fun createResponse(status: Status, mimeType: String, message: String): Response {
        val response = newFixedLengthResponse(status, mimeType, message)
        response.addHeader("Accept-Ranges", "bytes")
        return response
    }

    private fun getResponse(message: String): Response {
        return createResponse(Status.OK, "text/plain", message)
    }

    private fun getHref(path: String): String {
        val offset = path.indexOf("/", 0)
        val startIndex = path.indexOf("/", offset + 1)
        return path.substring(startIndex + 1)
    }
}