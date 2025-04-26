package movies;

import static spark.Spark.exception;
import static spark.Spark.get;
import static spark.Spark.ipAddress;
import static spark.Spark.port;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import spark.Request;
import spark.Response;

public class LeakyServer {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Logger LOG = LoggerFactory.getLogger(LeakyServer.class);
    
    // Deliberately leaky cache that never clears
    private static final Map<String, byte[]> MEMORY_LEAK_CACHE = new HashMap<>();
    private static final List<String> CACHED_KEYS = new ArrayList<>();
    private static final Random RANDOM = new Random();
    
    public static void main(String[] args) {
        port(8082);
        ipAddress("127.0.0.1");
        
        get("/", LeakyServer::homeEndpoint);
        get("/allocate", LeakyServer::allocateEndpoint);
        get("/stats", LeakyServer::statsEndpoint);
        get("/keys", LeakyServer::keysEndpoint);
        exception(Exception.class, (exception, request, response) -> exception.printStackTrace());
        
        var version = System.getProperty("dd.version");
        LOG.info("Running Leaky Server version " + (version != null ? version.toLowerCase() : "(not set)") + 
                " with pid " + ProcessHandle.current().pid());
    }
    
    private static Object homeEndpoint(Request req, Response res) {
        return replyJSON(res, Map.of(
            "service", "leaky-server",
            "endpoints", List.of("/allocate", "/stats", "/keys"),
            "description", "A deliberately leaky service for profiling demonstrations"
        ));
    }
    
    private static Object allocateEndpoint(Request req, Response res) {
        int sizeKb = Integer.parseInt(req.queryParamOrDefault("sizeKb", "1024"));
        int count = Integer.parseInt(req.queryParamOrDefault("count", "1"));
        
        LOG.info("Allocating " + count + " items of " + sizeKb + "KB each");
        
        for (int i = 0; i < count; i++) {
            String key = UUID.randomUUID().toString();
            byte[] data = new byte[sizeKb * 1024];
            RANDOM.nextBytes(data); // Fill with random data
            
            MEMORY_LEAK_CACHE.put(key, data);
            CACHED_KEYS.add(key);
        }
        
        return replyJSON(res, Map.of(
            "allocated", count,
            "sizeKb", sizeKb,
            "totalItems", MEMORY_LEAK_CACHE.size(),
            "totalMemoryMb", MEMORY_LEAK_CACHE.size() * sizeKb / 1024
        ));
    }
    
    private static Object statsEndpoint(Request req, Response res) {
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        
        return replyJSON(res, Map.of(
            "cachedItems", MEMORY_LEAK_CACHE.size(),
            "totalCachedMemoryMb", MEMORY_LEAK_CACHE.size() * 
                (MEMORY_LEAK_CACHE.isEmpty() ? 0 : MEMORY_LEAK_CACHE.values().iterator().next().length) / (1024 * 1024),
            "jvmTotalMemoryMb", runtime.totalMemory() / (1024 * 1024),
            "jvmFreeMemoryMb", runtime.freeMemory() / (1024 * 1024),
            "jvmUsedMemoryMb", usedMemory / (1024 * 1024),
            "jvmMaxMemoryMb", runtime.maxMemory() / (1024 * 1024)
        ));
    }
    
    private static Object keysEndpoint(Request req, Response res) {
        int limit = Integer.parseInt(req.queryParamOrDefault("limit", "100"));
        List<String> limitedKeys = CACHED_KEYS.size() <= limit ? 
            CACHED_KEYS : 
            CACHED_KEYS.subList(CACHED_KEYS.size() - limit, CACHED_KEYS.size());
        
        return replyJSON(res, Map.of(
            "totalKeys", CACHED_KEYS.size(),
            "keys", limitedKeys
        ));
    }
    
    private static Object replyJSON(Response res, Object data) {
        res.type("application/json");
        return GSON.toJson(data);
    }
}