import okhttp3.*
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import java.io.IOException

// Minimal dependency: implementation("com.squareup.okhttp3:okhttp:4.12.0")

fun main(args: Array<String>) {
    System.setProperty("okhttp.platform", "jdk9")
    // 1. Handle Arguments: Support "multi <url>" single command style
    if (args.isEmpty()) {
        println("Usage: multi <url> [optional_filename]")
        return
    }

    val url = args[0]

    // Auto-detect filename if not provided
    val outputName = if (args.size > 1) {
        args[1]
    } else {
        val fileName = url.substringAfterLast("/")
        if (fileName.isEmpty() || !fileName.contains(".")) "downloaded_file.bin" else fileName
    }

    // Save to CURRENT directory (where the command is run)
    val file = File(outputName)
    val outputPath = file.absolutePath

    val threadCount = 16

    println("Initializing High-Speed Downloader...")
    println("Target: $url")
    println("Output: $outputPath")
    println("Forcing $threadCount parallel connections...")

    // 2. Configure Dispatcher & Client
    val dispatcher = Dispatcher().apply {
        maxRequests = threadCount
        maxRequestsPerHost = threadCount
    }

    val client = OkHttpClient.Builder()
        .dispatcher(dispatcher)
        .connectionPool(ConnectionPool(threadCount, 5, TimeUnit.MINUTES))
        .retryOnConnectionFailure(true)
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    // 3. Fetch Content-Length
    var response: Response? = null
    try {
        val request = Request.Builder().url(url).head().build()
        response = client.newCall(request).execute()
    } catch (e: Exception) {
        println("Failed to connect: ${e.message}")
        return
    }

    if (!response.isSuccessful) {
        println("Failed to fetch file info: ${response.code}")
        return
    }

    val contentLength = response.header("Content-Length")?.toLong() ?: -1L
    response.close()

    if (contentLength == -1L) {
        println("Server does not support Content-Length. Cannot use parallel download.")
        return
    }

    println("Total Size: ${formatSize(contentLength)}")

    // 4. Prepare File
    if (file.exists()) file.delete()
    val raf = RandomAccessFile(file, "rw")
    raf.setLength(contentLength)
    raf.close()

    val partSize = contentLength / threadCount
    val latch = CountDownLatch(threadCount)
    val totalBytesDownloaded = AtomicLong(0)

    // 5. Monitor Thread
    val monitorThread = Thread {
        var lastBytes = 0L
        while (latch.count > 0) {
            try { Thread.sleep(1000) } catch (e: InterruptedException) { break }

            val currentBytes = totalBytesDownloaded.get()
            val bytesThisSecond = currentBytes - lastBytes
            lastBytes = currentBytes

            val speed = formatSize(bytesThisSecond) + "/s"
            val progress = (currentBytes.toDouble() / contentLength * 100).toInt()
            val remainingBytes = contentLength - currentBytes
            val etaSeconds = if (bytesThisSecond > 0) remainingBytes / bytesThisSecond else 0
            val eta = String.format("%02d:%02d:%02d", etaSeconds / 3600, (etaSeconds % 3600) / 60, etaSeconds % 60)
            val activeThreads = latch.count

            print("\rProgress: $progress% | Threads: $activeThreads | Speed: $speed | Downloaded: ${formatSize(currentBytes)} | ETA: $eta   ")
        }
    }.apply { start() }

    // 6. Launch Parallel Requests with RETRY Logic
    for (i in 0 until threadCount) {
        val start = i * partSize
        val end = if (i == threadCount - 1) contentLength - 1 else (start + partSize - 1)

        // Launch a thread that handles its own retry loop
        Thread {
            downloadChunkWithRetry(client, url, start, end, file, totalBytesDownloaded, latch, i)
        }.start()
    }

    latch.await()
    println("\n\nDownload Complete!")
    client.dispatcher.executorService.shutdown()
    System.exit(0) // Ensure clean exit for native image
}

// 7. Robust Retry Logic with RESUME Capabilities
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
    var attempt = 1
    val maxRetries = 100
    var success = false
    var currentPos = start // Track where we are in this specific chunk

    while (attempt <= maxRetries && !success) {
        try {
            // Safety: If we've somehow already finished this chunk, exit
            if (currentPos > end) {
                success = true
                latch.countDown()
                return
            }

            val rangeRequest = Request.Builder()
                .url(url)
                .header("Range", "bytes=$currentPos-$end") // KEY CHANGE: Request only remaining bytes
                .build()

            val response = client.newCall(rangeRequest).execute()
            if (!response.isSuccessful) {
                response.close() // Prevent connection leak on error
                // 416 means "Range Not Satisfiable" - usually implies we are already done
                if (response.code == 416) {
                    success = true
                    latch.countDown()
                    return
                }
                throw IOException("Server Error ${response.code}")
            }

            response.body?.source()?.use { source ->
                val threadRaf = RandomAccessFile(file, "rw")
                threadRaf.seek(currentPos) // KEY CHANGE: Seek to the resume position

                val buffer = ByteArray(65536)
                var bytesRead: Int
                while (source.read(buffer).also { bytesRead = it } != -1) {
                    threadRaf.write(buffer, 0, bytesRead)
                    totalBytes.addAndGet(bytesRead.toLong())
                    currentPos += bytesRead // KEY CHANGE: Advance our local position marker
                }
                threadRaf.close()
            }
            success = true
            latch.countDown()

        } catch (e: Exception) {
            // Quietly retry. Because we updated 'currentPos', the next loop will
            // automatically resume exactly where we failed.
            attempt++
            try { Thread.sleep(2000) } catch (x: Exception) {} // Backoff 2s
        }
    }

    if (!success) {
        println("\nThread $id failed permanently after $maxRetries attempts.")
        latch.countDown() // Prevent hang
    }
}

fun formatSize(v: Long): String {
    if (v < 1024) return "$v B"
    val z = (63 - java.lang.Long.numberOfLeadingZeros(v)) / 10
    return String.format("%.1f %sB", v.toDouble() / (1L shl (z * 10)), " KMGTPE"[z])
}
