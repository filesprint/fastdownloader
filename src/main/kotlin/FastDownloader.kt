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
    println("Forcing $threadCount parallel connections...")

    val dispatcher = Dispatcher().apply {
        maxRequests = threadCount
        maxRequestsPerHost = threadCount
    }

    val client = OkHttpClient.Builder()
        .dispatcher(dispatcher)
        .connectionPool(ConnectionPool(threadCount, 5, TimeUnit.MINUTES))
        .retryOnConnectionFailure(true)
        .protocols(listOf(Protocol.HTTP_1_1))
        // Disable gzip (critical for range correctness)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("Accept-Encoding", "identity")
                    .build()
            )
        }
        .build()

    /* -------------------------------------------------------
       GET CONTENT LENGTH (simple & optimistic)
       ------------------------------------------------------- */
    val contentLength = try {
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            resp.header("Content-Length")?.toLongOrNull() ?: run {
                println("Failed to determine file size.")
                return
            }
        }
    } catch (e: Exception) {
        println("Failed to connect: ${e.message}")
        return
    }

    println("Total Size: ${formatSize(contentLength)}")

    if (file.exists()) file.delete()
    RandomAccessFile(file, "rw").use { it.setLength(contentLength) }

    val partSize = contentLength / threadCount
    val latch = CountDownLatch(threadCount)
    val totalBytes = AtomicLong(0)

    /* -------------------------------------------------------
       PROGRESS MONITOR (FULL INFO)
       ------------------------------------------------------- */
    Thread {
        var lastBytes = 0L
        var lastTime = System.currentTimeMillis()

        while (latch.count > 0) {
            Thread.sleep(1000)

            val nowBytes = totalBytes.get()
            val nowTime = System.currentTimeMillis()

            val bytesDiff = nowBytes - lastBytes
            val timeDiff = (nowTime - lastTime) / 1000.0

            val speed = if (timeDiff > 0) bytesDiff / timeDiff else 0.0
            val progress = nowBytes * 100.0 / contentLength
            val remaining = contentLength - nowBytes
            val etaSeconds = if (speed > 0) (remaining / speed).toLong() else -1

            val eta = if (etaSeconds >= 0)
                String.format(
                    "%02d:%02d:%02d",
                    etaSeconds / 3600,
                    (etaSeconds % 3600) / 60,
                    etaSeconds % 60
                )
            else "--:--:--"

            print(
                "\rProgress: %.2f%% | Downloaded: %s | Speed: %s/s | ETA: %s | Threads: %d   "
                    .format(
                        progress,
                        formatSize(nowBytes),
                        formatSize(speed.toLong()),
                        eta,
                        latch.count
                    )
            )

            lastBytes = nowBytes
            lastTime = nowTime
        }
    }.start()

    /* -------------------------------------------------------
       PARALLEL DOWNLOAD
       ------------------------------------------------------- */
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