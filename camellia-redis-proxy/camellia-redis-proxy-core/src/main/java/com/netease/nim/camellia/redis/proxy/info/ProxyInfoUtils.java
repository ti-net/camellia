package com.netease.nim.camellia.redis.proxy.info;

import com.alibaba.fastjson.JSONObject;
import com.netease.nim.camellia.core.util.ReadableResourceTableUtil;
import com.netease.nim.camellia.redis.proxy.command.Command;
import com.netease.nim.camellia.redis.proxy.netty.GlobalRedisProxyEnv;
import com.netease.nim.camellia.redis.proxy.upstream.IUpstreamClientTemplateFactory;
import com.netease.nim.camellia.redis.proxy.upstream.UpstreamRedisClientTemplate;
import com.netease.nim.camellia.redis.proxy.upstream.connection.RedisConnection;
import com.netease.nim.camellia.redis.proxy.upstream.connection.RedisConnectionAddr;
import com.netease.nim.camellia.redis.proxy.enums.RedisCommand;
import com.netease.nim.camellia.redis.proxy.monitor.*;
import com.netease.nim.camellia.redis.proxy.netty.ChannelInfo;
import com.netease.nim.camellia.redis.proxy.reply.BulkReply;
import com.netease.nim.camellia.redis.proxy.reply.ErrorReply;
import com.netease.nim.camellia.redis.proxy.reply.Reply;
import com.netease.nim.camellia.tools.utils.CamelliaMapUtils;
import com.netease.nim.camellia.redis.proxy.util.ErrorLogCollector;
import com.netease.nim.camellia.redis.proxy.util.Utils;
import io.netty.util.concurrent.DefaultThreadFactory;

import java.lang.management.*;
import java.nio.charset.StandardCharsets;
import java.text.CharacterIterator;
import java.text.StringCharacterIterator;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 *
 * Created by caojiajun on 2021/6/24
 */
public class ProxyInfoUtils {

    private static final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(8), new DefaultThreadFactory("proxy-info"));

    public static final String VERSION = "v1.2.21";
    private static final RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();
    private static final OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
    private static final List<GarbageCollectorMXBean> garbageCollectorMXBeanList = ManagementFactory.getGarbageCollectorMXBeans();

    private static final AtomicLong commandsCount = new AtomicLong();
    private static final AtomicLong readCommandsCount = new AtomicLong();
    private static final AtomicLong writeCommandsCount = new AtomicLong();

    private static double avgCommandsQps = 0.0;
    private static double avgReadCommandsQps = 0.0;
    private static double avgWriteCommandsQps = 0.0;

    private static double lastCommandQps = 0.0;
    private static double lastReadCommandQps = 0.0;
    private static double lastWriteCommandQps = 0.0;

    /**
     * update every 1 seconds
     * @param readQps readQps
     * @param writeQps writeQps
     */
    public static void updateLastQps(long readQps, long writeQps) {
        commandsCount.addAndGet(readQps + writeQps);
        readCommandsCount.addAndGet(readQps);
        writeCommandsCount.addAndGet(writeQps);

        avgCommandsQps = commandsCount.get() / (runtimeMXBean.getUptime() / 1000.0);
        avgReadCommandsQps = readCommandsCount.get() / (runtimeMXBean.getUptime() / 1000.0);
        avgWriteCommandsQps = writeCommandsCount.get() / (runtimeMXBean.getUptime() / 1000.0);

        lastCommandQps = readQps + writeQps;
        lastReadCommandQps = readQps;
        lastWriteCommandQps = writeQps;
    }

    public static CompletableFuture<Reply> getInfoReply(Command command, IUpstreamClientTemplateFactory factory) {
        CompletableFuture<Reply> future = new CompletableFuture<>();
        try {
            executor.submit(() -> {
                Reply reply = generateInfoReply(command, factory);
                future.complete(reply);
            });
            return future;
        } catch (Exception e) {
            ErrorLogCollector.collect(ProxyInfoUtils.class, "submit generateInfoReply task error", e);
            future.complete(ErrorReply.TOO_BUSY);
            return future;
        }
    }

    public static String generateProxyInfo(Map<String, String> params) {
        String json = params.get("json");
        boolean parseJson = json != null && json.equalsIgnoreCase("true");
        String section = params.get("section");
        if (section != null) {
            if (section.equalsIgnoreCase("upstream-info")) {
                String bid = params.get("bid");
                String bgroup = params.get("bgroup");
                if (bid == null || bgroup == null) {
                    return UpstreamInfoUtils.upstreamInfo(null, null, GlobalRedisProxyEnv.getClientTemplateFactory(), parseJson);
                }
                try {
                    Long.parseLong(bid);
                } catch (NumberFormatException e) {
                    return parseResponse(ErrorReply.SYNTAX_ERROR, parseJson);
                }
                return UpstreamInfoUtils.upstreamInfo(Long.parseLong(bid), bgroup, GlobalRedisProxyEnv.getClientTemplateFactory(), parseJson);
            } else {
                Reply reply = generateInfoReply(new Command(new byte[][]{RedisCommand.INFO.raw(), section.getBytes(StandardCharsets.UTF_8)}), GlobalRedisProxyEnv.getClientTemplateFactory());
                return parseResponse(reply, parseJson);
            }
        } else {
            Reply reply = generateInfoReply(new Command(new byte[][]{RedisCommand.INFO.raw()}), GlobalRedisProxyEnv.getClientTemplateFactory());
            return parseResponse(reply, parseJson);
        }
    }

    private static String parseResponse(Reply reply, boolean parseJson) {
        String string = replyToString(reply);
        if (!parseJson) return string;
        String[] split = string.split("\r\n");
        JSONObject result = new JSONObject();
        if (split.length == 1) {
            result.put("code", 500);
            result.put("msg", split[0]);
            return result.toJSONString();
        }
        result.put("code", 200);
        result.put("msg", "success");
        JSONObject data = new JSONObject();
        String key = null;
        JSONObject item = new JSONObject();
        for (String line : split) {
            line = line.replaceAll("\\s*", "");
            if (line.startsWith("#")) {
                line = line.replaceAll("#", "");
                if (key != null) {
                    data.put(key, item);
                    item = new JSONObject();
                }
                key = line;
            } else {
                int index;
                if (line.startsWith("upstream_redis_nums")) {
                    index = line.lastIndexOf(":");
                } else {
                    index = line.indexOf(":");
                }
                if (index > 0) {
                    String itemKey = line.substring(0, index);
                    String itemValue = line.substring(index + 1);
                    if (key != null && key.equalsIgnoreCase("Route")) {
                        try {
                            JSONObject itemValueJson = JSONObject.parseObject(itemValue);
                            item.put(itemKey, itemValueJson);
                        } catch (Exception e) {
                            item.put(itemKey, itemValue);
                        }
                    } else {
                        item.put(itemKey, itemValue);
                    }
                }
            }
        }
        if (key != null) {
            data.put(key, item);
        }
        result.put("data", data);
        return result.toJSONString();
    }

    private static String replyToString(Reply reply) {
        if (reply == null) {
            reply = ErrorReply.SYNTAX_ERROR;
        }
        if (reply instanceof BulkReply) {
            return reply.toString();
        } else if (reply instanceof ErrorReply) {
            return ((ErrorReply) reply).getError();
        } else {
            return ErrorReply.SYNTAX_ERROR.getError();
        }
    }

    public static Reply generateInfoReply(Command command, IUpstreamClientTemplateFactory factory) {
        try {
            StringBuilder builder = new StringBuilder();
            byte[][] objects = command.getObjects();
            if (objects.length == 1) {
                builder.append(getServer()).append("\r\n");
                builder.append(getClients()).append("\r\n");
                builder.append(getRoutes()).append("\r\n");
                builder.append(getUpstream()).append("\r\n");
                builder.append(getMemory()).append("\r\n");
                builder.append(getGC()).append("\r\n");
                builder.append(getStats()).append("\r\n");
            } else {
                if (objects.length == 2) {
                    String section = Utils.bytesToString(objects[1]);
                    if (section.equalsIgnoreCase("server")) {
                        builder.append(getServer()).append("\r\n");
                    } else if (section.equalsIgnoreCase("clients")) {
                        builder.append(getClients()).append("\r\n");
                    } else if (section.equalsIgnoreCase("route")) {
                        builder.append(getRoutes()).append("\r\n");
                    } else if (section.equalsIgnoreCase("upstream")) {
                        builder.append(getUpstream()).append("\r\n");
                    } else if (section.equalsIgnoreCase("memory")) {
                        builder.append(getMemory()).append("\r\n");
                    } else if (section.equalsIgnoreCase("gc")) {
                        builder.append(getGC()).append("\r\n");
                    } else if (section.equalsIgnoreCase("stats")) {
                        builder.append(getStats()).append("\r\n");
                    } else if (section.equalsIgnoreCase("upstream-info")) {
                        builder.append(UpstreamInfoUtils.upstreamInfo(null, null, factory, false)).append("\r\n");
                    }
                } else if (objects.length == 4) {
                    String section = Utils.bytesToString(objects[1]);
                    if (section.equalsIgnoreCase("upstream-info")) {
                        long bid;
                        String bgroup;
                        try {
                            bid = Utils.bytesToNum(objects[2]);
                            bgroup = Utils.bytesToString(objects[3]);
                        } catch (Exception e) {
                            return ErrorReply.SYNTAX_ERROR;
                        }
                        builder.append(UpstreamInfoUtils.upstreamInfo(bid, bgroup, factory, false)).append("\r\n");
                    } else {
                        return ErrorReply.SYNTAX_ERROR;
                    }
                } else {
                    return ErrorReply.SYNTAX_ERROR;
                }
            }
            return new BulkReply(Utils.stringToBytes(builder.toString()));
        } catch (Exception e) {
            ErrorLogCollector.collect(ProxyInfoUtils.class, "getInfoReply error", e);
            return new ErrorReply("generate proxy info error");
        }
    }

    private static String getServer() {
        StringBuilder builder = new StringBuilder();
        builder.append("# Server").append("\r\n");
        builder.append("camellia_redis_proxy_version:" + VERSION).append("\r\n");
        builder.append("redis_version:7.0.11").append("\r\n");//spring actuator默认会使用info命令返回的redis_version字段来做健康检查，这里直接返回一个固定的版本号
        builder.append("available_processors:").append(osBean.getAvailableProcessors()).append("\r\n");
        builder.append("netty_boss_thread:").append(GlobalRedisProxyEnv.getBossThread()).append("\r\n");
        builder.append("netty_work_thread:").append(GlobalRedisProxyEnv.getWorkThread()).append("\r\n");
        builder.append("arch:").append(osBean.getArch()).append("\r\n");
        builder.append("os_name:").append(osBean.getName()).append("\r\n");
        builder.append("os_version:").append(osBean.getVersion()).append("\r\n");
        builder.append("system_load_average:").append(osBean.getSystemLoadAverage()).append("\r\n");
        if ((GlobalRedisProxyEnv.getPort() <= 0 && GlobalRedisProxyEnv.getTlsPort() > 0) || (GlobalRedisProxyEnv.getPort() == GlobalRedisProxyEnv.getTlsPort())) {
            builder.append("tcp_port:").append(GlobalRedisProxyEnv.getTlsPort()).append("\r\n");
        } else {
            builder.append("tcp_port:").append(GlobalRedisProxyEnv.getPort()).append("\r\n");
            builder.append("tcp_tls_port:").append(GlobalRedisProxyEnv.getTlsPort()).append("\r\n");
        }
        String udsPath = GlobalRedisProxyEnv.getUdsPath();
        if (udsPath != null) {
            builder.append("uds_path:").append(udsPath).append("\r\n");
        }
        builder.append("http_console_port:").append(GlobalRedisProxyEnv.getConsolePort()).append("\r\n");
        long uptime = runtimeMXBean.getUptime();
        long uptimeInSeconds = uptime / 1000L;
        long uptimeInDays = uptime / (1000L * 60 * 60 * 24);
        builder.append("uptime_in_seconds:").append(uptimeInSeconds).append("\r\n");
        builder.append("uptime_in_days:").append(uptimeInDays).append("\r\n");
        builder.append("vm_vendor:").append(runtimeMXBean.getVmVendor()).append("\r\n");
        builder.append("vm_name:").append(runtimeMXBean.getVmName()).append("\r\n");
        builder.append("vm_version:").append(runtimeMXBean.getVmVersion()).append("\r\n");
        builder.append("jvm_info:").append(System.getProperties().get("java.vm.info")).append("\r\n");
        builder.append("java_version:").append(System.getProperties().get("java.version")).append("\r\n");
        return builder.toString();
    }

    private static String getClients() {
        StringBuilder builder = new StringBuilder();
        builder.append("# Clients").append("\r\n");
        builder.append("connect_clients:").append(ChannelMonitor.connect()).append("\r\n");
        ConcurrentHashMap<String, AtomicLong> map = new ConcurrentHashMap<>();
        for (Map.Entry<String, ChannelInfo> entry : ChannelMonitor.getChannelMap().entrySet()) {
            Long bid = entry.getValue().getBid();
            String bgroup = entry.getValue().getBgroup();
            String key;
            if (bid == null || bgroup == null) {
                key = "connect_clients_default_default";
            } else {
                key = "connect_clients_" + bid + "_" + bgroup;
            }
            AtomicLong count = CamelliaMapUtils.computeIfAbsent(map, key, k -> new AtomicLong());
            count.incrementAndGet();
        }
        for (Map.Entry<String, AtomicLong> entry : map.entrySet()) {
            builder.append(entry.getKey()).append(":").append(entry.getValue().get()).append("\r\n");
        }
        return builder.toString();
    }

    private static String getStats() {
        StringBuilder builder = new StringBuilder();
        builder.append("# Stats").append("\r\n");
        builder.append("commands_count:").append(commandsCount).append("\r\n");
        builder.append("read_commands_count:").append(readCommandsCount).append("\r\n");
        builder.append("write_commands_count:").append(writeCommandsCount).append("\r\n");
        builder.append("avg_commands_qps:").append(String.format("%.2f", avgCommandsQps)).append("\r\n");
        builder.append("avg_read_commands_qps:").append(String.format("%.2f", avgReadCommandsQps)).append("\r\n");
        builder.append("avg_write_commands_qps:").append(String.format("%.2f", avgWriteCommandsQps)).append("\r\n");
        builder.append("last_commands_qps:").append(String.format("%.2f", lastCommandQps)).append("\r\n");
        builder.append("last_read_commands_qps:").append(String.format("%.2f", lastReadCommandQps)).append("\r\n");
        builder.append("last_write_commands_qps:").append(String.format("%.2f", lastWriteCommandQps)).append("\r\n");
        return builder.toString();
    }

    private static String getRoutes() {
        StringBuilder builder = new StringBuilder();
        builder.append("# Route").append("\r\n");
        ConcurrentHashMap<String, UpstreamRedisClientTemplate> templateMap = RouteConfMonitor.getTemplateMap();
        builder.append("route_nums:").append(templateMap.size()).append("\r\n");
        for (Map.Entry<String, UpstreamRedisClientTemplate> entry : templateMap.entrySet()) {
            String key = entry.getKey();
            String[] split = key.split("\\|");
            Long bid = null;
            if (!split[0].equals("null")) {
                bid = Long.parseLong(split[0]);
            }
            String bgroup = null;
            if (!split[1].equals("null")) {
                bgroup = split[1];
            }
            UpstreamRedisClientTemplate template = entry.getValue();
            String routeConf = ReadableResourceTableUtil.readableResourceTable(PasswordMaskUtils.maskResourceTable(template.getResourceTable()));
            builder.append("route_conf_").append(bid == null ? "default" : bid).append("_").append(bgroup == null ? "default" : bgroup).append(":").append(routeConf).append("\r\n");
        }
        return builder.toString();
    }

    private static String getUpstream() {
        StringBuilder builder = new StringBuilder();
        builder.append("# Upstream").append("\r\n");
        ConcurrentHashMap<RedisConnectionAddr, ConcurrentHashMap<String, RedisConnection>> redisClientMap = RedisConnectionMonitor.getRedisClientMap();
        int upstreamRedisNums = 0;
        for (Map.Entry<RedisConnectionAddr, ConcurrentHashMap<String, RedisConnection>> entry : redisClientMap.entrySet()) {
            upstreamRedisNums += entry.getValue().size();
        }
        builder.append("upstream_redis_nums:").append(upstreamRedisNums).append("\r\n");
        for (Map.Entry<RedisConnectionAddr, ConcurrentHashMap<String, RedisConnection>> entry : redisClientMap.entrySet()) {
            builder.append("upstream_redis_nums").append("[").append(PasswordMaskUtils.maskAddr(entry.getKey().getUrl())).append("]").append(":").append(entry.getValue().size()).append("\r\n");
        }
        return builder.toString();
    }

    private static String getMemory() {
        StringBuilder builder = new StringBuilder();
        builder.append("# Memory").append("\r\n");
        MemoryInfo memoryInfo = MemoryInfoUtils.getMemoryInfo();
        long freeMemory = memoryInfo.getFreeMemory();
        long totalMemory = memoryInfo.getTotalMemory();
        long maxMemory = memoryInfo.getMaxMemory();
        builder.append("free_memory:").append(freeMemory).append("\r\n");
        builder.append("free_memory_human:").append(humanReadableByteCountBin(freeMemory)).append("\r\n");
        builder.append("total_memory:").append(totalMemory).append("\r\n");
        builder.append("total_memory_human:").append(humanReadableByteCountBin(totalMemory)).append("\r\n");
        builder.append("max_memory:").append(maxMemory).append("\r\n");
        builder.append("max_memory_human:").append(humanReadableByteCountBin(maxMemory)).append("\r\n");
        builder.append("heap_memory_init:").append(memoryInfo.getHeapMemoryInit()).append("\r\n");
        builder.append("heap_memory_init_human:").append(humanReadableByteCountBin(memoryInfo.getHeapMemoryInit())).append("\r\n");
        builder.append("heap_memory_used:").append(memoryInfo.getHeapMemoryUsed()).append("\r\n");
        builder.append("heap_memory_used_human:").append(humanReadableByteCountBin(memoryInfo.getHeapMemoryUsed())).append("\r\n");
        builder.append("heap_memory_max:").append(memoryInfo.getHeapMemoryMax()).append("\r\n");
        builder.append("heap_memory_max_human:").append(humanReadableByteCountBin(memoryInfo.getHeapMemoryMax())).append("\r\n");
        builder.append("heap_memory_committed:").append(memoryInfo.getHeapMemoryCommitted()).append("\r\n");
        builder.append("heap_memory_committed_human:").append(humanReadableByteCountBin(memoryInfo.getHeapMemoryCommitted())).append("\r\n");
        builder.append("non_heap_memory_init:").append(memoryInfo.getNonHeapMemoryInit()).append("\r\n");
        builder.append("non_heap_memory_init_human:").append(humanReadableByteCountBin(memoryInfo.getNonHeapMemoryInit())).append("\r\n");
        builder.append("non_heap_memory_used:").append(memoryInfo.getNonHeapMemoryUsed()).append("\r\n");
        builder.append("non_heap_memory_used_human:").append(humanReadableByteCountBin(memoryInfo.getNonHeapMemoryUsed())).append("\r\n");
        builder.append("non_heap_memory_max:").append(memoryInfo.getNonHeapMemoryMax()).append("\r\n");
        builder.append("non_heap_memory_max_human:").append(humanReadableByteCountBin(memoryInfo.getNonHeapMemoryMax())).append("\r\n");
        builder.append("non_heap_memory_committed:").append(memoryInfo.getNonHeapMemoryCommitted()).append("\r\n");
        builder.append("non_heap_memory_committed_human:").append(humanReadableByteCountBin(memoryInfo.getNonHeapMemoryCommitted())).append("\r\n");
        builder.append("netty_direct_memory:").append(memoryInfo.getNettyDirectMemory()).append("\r\n");
        builder.append("netty_direct_memory_human:").append(humanReadableByteCountBin(memoryInfo.getNettyDirectMemory())).append("\r\n");
        return builder.toString();
    }

    private static String getGC() {
        StringBuilder builder = new StringBuilder();
        builder.append("# GC").append("\r\n");
        if (garbageCollectorMXBeanList != null) {
            for (int i=0; i<garbageCollectorMXBeanList.size(); i++) {
                GarbageCollectorMXBean garbageCollectorMXBean = garbageCollectorMXBeanList.get(i);
                builder.append("gc").append(i).append("_name:").append(garbageCollectorMXBean.getName()).append("\r\n");
                builder.append("gc").append(i).append("_collection_count:").append(garbageCollectorMXBean.getCollectionCount()).append("\r\n");
                builder.append("gc").append(i).append("_collection_time:").append(garbageCollectorMXBean.getCollectionCount()).append("\r\n");
            }
        }
        return builder.toString();
    }

    public static String humanReadableByteCountBin(long bytes) {
        long absB = bytes == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(bytes);
        if (absB < 1024) {
            return bytes + "B";
        }
        long value = absB;
        CharacterIterator ci = new StringCharacterIterator("KMGTPE");
        for (int i = 40; i >= 0 && absB > 0xfffccccccccccccL >> i; i -= 10) {
            value >>= 10;
            ci.next();
        }
        value *= Long.signum(bytes);
        return String.format("%.2f%c", value / 1024.0, ci.current());
    }
}
