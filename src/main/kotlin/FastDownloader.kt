import okhttp3.*
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import java.io.IOException

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Usage: multi <url> [optional_filename]")
        return
    }

    val url = args[0]

    val outputName = if (args.size > 1) {
        args[1]
    } else {
        val name = url.substringAfterLast("/")
        if (name.isEmpty() || !name.contains(".")) "downloaded_file.bin" else name
    }

    val file = File(outputName)
    val threadCount = 16

    println("Initializing High-Speed Downloader...")
    println("Target: $url")
    println("Output: ${file.absolutePath}")

    val dispatcher = Dispatcher().apply {
        maxRequests = threadCount
        maxRequestsPerHost = threadCount
    }

    val client = OkHttpClient.Builder()
        .dispatcher(dispatcher)
        .connectionPool(ConnectionPool(threadCount, 5, TimeUnit.MINUTES))
        .retryOnConnectionFailure(true)
        .protocols(listOf(Protocol.HTTP_1_1))
        // IMPORTANT: Disable gzip (range correctness)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("Accept-Encoding", "identity")
                    .build()
            )
        }
        .build()

    /* -------------------------------------------------------
       RANGE + REDIRECT PROBE (FINAL ENDPOINT ONLY)
       ------------------------------------------------------- */
    val probeRequest = Request.Builder()
        .url(url)
        .get()
        .header("Range", "bytes=0-0")
        .build()

    val (supportsRange, contentLength) = try {
        client.newCall(probeRequest).execute().use { resp ->
            val contentRange = resp.header("Content-Range")
            if (resp.code == 206 && contentRange != null) {
                true to contentRange.substringAfter("/").toLong()
            } else {
                false to (resp.header("Content-Length")?.toLongOrNull() ?: -1L)
            }
        }
    } catch (e: Exception) {
        println("Failed to connect: ${e.message}")
        return
    }

    /* -------------------------------------------------------
       FALLBACK: SINGLE STREAM
       ------------------------------------------------------- */
    if (!supportsRange || contentLength <= 0) {
        println("Server does not support parallel range download.")
        println("Falling back to single-stream download...")

        singleStreamDownload(client, url, file)
        println("\nDownload Complete!")
        return
    }

    /* -------------------------------------------------------
       PARALLEL DOWNLOAD
       ------------------------------------------------------- */
    println("Range supported. Using parallel download.")
    println("Total Size: ${formatSize(contentLength)}")
    println("Forcing $threadCount parallel connections...")

    if (file.exists()) file.delete()
    RandomAccessFile(file, "rw").use { it.setLength(contentLength) }

    val partSize = contentLength / threadCount
    val latch = CountDownLatch(threadCount)
    val totalBytes = AtomicLong(0)

// Progress monitor
    Thread {
        var last = 0L
        while (latch.count > 0) {
            Thread.sleep(1000)
            val now = totalBytes.get()
            val speed = formatSize(now - last) + "/s"
            last = now
            val progress = (now * 100 / contentLength)
            print("\rProgress: $progress% | Threads: ${latch.count} | Speed: $speed")
        }
    }.start()

    for (i in 0 until threadCount) {
        val start = i * partSize
        val end = if (i == threadCount - 1) contentLength - 1 else start + partSize - 1

        Thread {
            downloadChunkWithRetry(
                client, url, start, end, file, totalBytes, latch, i
            )
        }.start()
    }

    latch.await()
    println("\n\nDownload Complete!")
    client.dispatcher.executorService.shutdown()
    System.exit(0)

}

/* -------------------------------------------------------
SINGLE STREAM (NO RANGE)
------------------------------------------------------- */
fun singleStreamDownload(client: OkHttpClient, url: String, file: File) {
    val request = Request.Builder().url(url).build()

    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            throw IOException("Download failed: ${response.code}")
        }

        response.body!!.byteStream().use { input ->
            file.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                }
            }
        }
    }

}

/* -------------------------------------------------------
PARALLEL CHUNK DOWNLOAD (RETRY SAFE)
------------------------------------------------------- */
fun downloadChunkWithRetry(
    client: OkHttpClient,
    url: String,
    start: Long,
    end: Long,
    file: File,
    totalBytes: AtomicLong,
    latch: CountDownLatch,
    id: Int
) {
    var currentPos = start
    var attempt = 1
    val maxRetries = 100

    while (attempt <= maxRetries) {
        try {
            if (currentPos > end) break

            val request = Request.Builder()
                .url(url)
                .header("Range", "bytes=$currentPos-$end")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.code != 206) {
                    throw IOException("Range not honored (code ${response.code})")
                }

                response.body!!.source().use { source ->
                    RandomAccessFile(file, "rw").use { raf ->
                        raf.seek(currentPos)
                        val buffer = ByteArray(64 * 1024)

                        while (currentPos <= end) {
                            val read = source.read(buffer)
                            if (read == -1) break

                            val toWrite =
                                minOf(read.toLong(), end - currentPos + 1).toInt()

                            raf.write(buffer, 0, toWrite)
                            currentPos += toWrite
                            totalBytes.addAndGet(toWrite.toLong())
                        }
                    }
                }
            }

            if (currentPos > end) break

        } catch (e: Exception) {
            attempt++
            Thread.sleep(2000)
        }
    }

    if (currentPos <= end) {
        println("\nThread $id failed permanently.")
    }

    latch.countDown()

}

fun formatSize(v: Long): String {
    if (v < 1024) return "$v B"
    val z = (63 - java.lang.Long.numberOfLeadingZeros(v)) / 10
    return String.format(
        "%.1f %sB",
        v.toDouble() / (1L shl (z * 10)),
        " KMGTPE"[z]
    )
}