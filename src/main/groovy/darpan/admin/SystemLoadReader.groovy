package darpan.admin

import groovy.transform.CompileDynamic

import java.lang.management.ManagementFactory
import java.lang.management.MemoryPoolMXBean
import java.lang.management.MemoryType
import java.lang.management.MemoryUsage
import java.sql.Timestamp

/**
 * DAR-ADMIN-008. Load figures for the admin live board, read from the JVM that answers the request.
 *
 * Every figure is independently best-effort and null when this JVM cannot report it, so one missing
 * MXBean never blanks the rest. These describe ONE process: with several backend containers, each
 * request sees only its own.
 */
@CompileDynamic
class SystemLoadReader {

    static Map<String, Object> read(def ec) {
        Runtime runtime = Runtime.getRuntime()
        long heapUsed = runtime.totalMemory() - runtime.freeMemory()
        long heapMax = runtime.maxMemory()
        long uptimeMs = ManagementFactory.runtimeMXBean.uptime

        Map<String, Object> disk = diskOf(System.getProperty("moqui.runtime"))
        return [
                heapUsedBytes      : heapUsed,
                heapMaxBytes       : heapMax,
                heapAfterGcPercent : heapAfterGcPercent(heapMax) ?: percent(heapUsed, heapMax),
                processCpuPercent  : processCpuPercent(),
                systemLoadAverage  : systemLoadAverage(),
                availableProcessors: runtime.availableProcessors(),
                threadCount        : ManagementFactory.threadMXBean.threadCount,
                diskFreeBytes      : disk.free,
                diskTotalBytes     : disk.total,
                diskFreePercent    : disk.free != null ? percent(disk.free as long, disk.total as long) : null,
                workerPool         : workerPool(ec),
                uptimeMs           : uptimeMs,
                startedAt          : new Timestamp(System.currentTimeMillis() - uptimeMs),
        ]
    }

    /**
     * Old-generation occupancy as of the last collection, the figure that means "memory pressure".
     * Live heap swings with every allocation burst and reads 90% on a healthy JVM just before a young GC.
     */
    private static Integer heapAfterGcPercent(long heapMax) {
        try {
            MemoryPoolMXBean oldGen = ManagementFactory.memoryPoolMXBeans.find { MemoryPoolMXBean pool ->
                pool.type == MemoryType.HEAP && pool.collectionUsage != null && (pool.name =~ /(?i)old|tenured/)
            }
            MemoryUsage usage = oldGen?.collectionUsage
            if (usage == null || usage.used <= 0) return null
            long max = usage.max > 0 ? usage.max : heapMax
            return percent(usage.used, max)
        } catch (Throwable ignored) {
            return null
        }
    }

    private static Integer processCpuPercent() {
        try {
            def os = ManagementFactory.operatingSystemMXBean
            if (!(os instanceof com.sun.management.OperatingSystemMXBean)) return null
            double load = ((com.sun.management.OperatingSystemMXBean) os).processCpuLoad
            return load < 0 ? null : (int) Math.round(load * 100)
        } catch (Throwable ignored) {
            return null
        }
    }

    private static Double systemLoadAverage() {
        double load = ManagementFactory.operatingSystemMXBean.systemLoadAverage
        return load < 0 ? null : Math.round(load * 100) / 100.0d
    }

    private static Map<String, Object> diskOf(String path) {
        try {
            File root = path ? new File(path) : null
            if (root == null || !root.exists() || root.totalSpace <= 0) return [free: null, total: null]
            return [free: root.usableSpace, total: root.totalSpace]
        } catch (Throwable ignored) {
            return [free: null, total: null]
        }
    }

    /** Moqui's async worker pool (ExecutionContextFactoryImpl.workerPool), where scan#DueAutomations runs. */
    private static Map<String, Object> workerPool(def ec) {
        try {
            def pool = ec.factory.workerPool
            if (pool == null) return null
            return [active: pool.activeCount, max: pool.maximumPoolSize, queued: pool.queue.size()]
        } catch (Throwable ignored) {
            return null
        }
    }

    private static Integer percent(long part, long whole) {
        return whole > 0 ? (int) Math.round(part * 100.0d / whole) : null
    }
}
