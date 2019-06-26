package org.micro.neural.config.store;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.pubsub.RedisPubSubListener;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.async.RedisPubSubAsyncCommands;
import io.lettuce.core.support.ConnectionPoolSupport;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.micro.neural.common.URL;
import org.micro.neural.common.utils.SerializeUtils;
import org.micro.neural.extension.Extension;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * The Store by Redis
 * <p>
 *
 * @author lry
 **/
@Slf4j
@Extension(RedisStore.IDENTITY)
public class RedisStore implements IStore {

    public static final String IDENTITY = "redis";
    public static final String SENTINEL = "sentinel";
    public static final String PASSWORD = "password";

    private RedisClient redisClient = null;
    private GenericObjectPool<StatefulRedisConnection<String, String>> objectPool;
    private final Map<IStoreListener, RedisPubSubAsyncCommands<String, String>> subscribed = new ConcurrentHashMap<>();

    @Override
    public void initialize(URL url) {
        RedisURI redisURI;
        String category = url.getParameter(URL.CATEGORY_KEY, IDENTITY);
        if (SENTINEL.equals(category)) {
            redisURI = RedisURI.Builder.sentinel(url.getHost(), url.getPort()).build();
        } else {
            redisURI = RedisURI.Builder.redis(url.getHost(), url.getPort()).build();
        }

        String password = url.getParameter(PASSWORD);
        if (password != null && password.length() > 0) {
            redisURI.setPassword(password);
        }

        this.redisClient = RedisClient.create(redisURI);
        this.objectPool = ConnectionPoolSupport.createGenericObjectPool(
                () -> redisClient.connect(), new GenericObjectPoolConfig());
    }

    private StatefulRedisConnection<String, String> borrowObject() {
        try {
            return objectPool.borrowObject();
        } catch (Exception e) {
            throw new RuntimeException("The borrow object is exception", e);
        }
    }

    @Override
    public void batchUpOrAdd(long expire, Map<String, Long> data) {
        try (StatefulRedisConnection<String, String> connection = borrowObject()) {
            RedisAsyncCommands<String, String> commands = connection.async();
            for (Map.Entry<String, Long> entry : data.entrySet()) {
                commands.incrby(entry.getKey(), entry.getValue());
                commands.pexpire(entry.getKey(), expire);
            }
        }
    }

    @Override
    public void add(String space, String key, Object data) {
        try (StatefulRedisConnection<String, String> connection = borrowObject()) {
            connection.sync().hset(space, key, SerializeUtils.serialize(data));
        }
    }

    @Override
    public Set<String> searchKeys(String space, String keyword) {
        try (StatefulRedisConnection<String, String> connection = borrowObject()) {
            List<String> keys = connection.sync().hkeys(space);
            if (keys == null || keys.isEmpty()) {
                return Collections.emptySet();
            }

            return new HashSet<>(keys);
        }
    }

    @Override
    public <C> C query(String space, String key, Class<C> clz) {
        try (StatefulRedisConnection<String, String> connection = borrowObject()) {
            String json = connection.sync().hget(space, key);
            if (json == null || json.length() == 0) {
                return null;
            }

            return SerializeUtils.deserialize(clz, json);
        }
    }

    @Override
    public String get(String key) {
        try (StatefulRedisConnection<String, String> connection = borrowObject()) {
            return connection.sync().get(key);
        }
    }

    @Override
    public <T> T eval(Class<T> type, String script, Long timeout, List<Object> keys) {
        ScriptOutputType scriptOutputType;
        String typeName = type.getName();
        if (Boolean.class.getName().equals(typeName)) {
            scriptOutputType = ScriptOutputType.BOOLEAN;
        } else if (Integer.class.getName().equals(typeName)) {
            scriptOutputType = ScriptOutputType.INTEGER;
        } else {
            scriptOutputType = ScriptOutputType.MULTI;
        }

        String[] keyArray = new String[keys.size()];
        for (int i = 0; i < keys.size(); i++) {
            Object obj = keys.get(i);
            if (obj == null) {
                throw new IllegalArgumentException("The key[" + i + "] is null");
            }

            keyArray[i] = String.valueOf(obj);
        }

        try (StatefulRedisConnection<String, String> connection = borrowObject()) {
            RedisFuture<T> redisFuture = connection.async().eval(script, scriptOutputType, keyArray);

            T result;
            try {
                result = redisFuture.get(timeout, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage(), e);
            }

            if (String.class.getName().equals(typeName)) {
                return result;
            }

            return SerializeUtils.deserialize(type, (String) result);
        }
    }

    @Override
    public Map<String, String> pull(String key) {
        try (StatefulRedisConnection<String, String> connection = borrowObject()) {
            return connection.sync().hgetall(key);
        }
    }

    @Override
    public void publish(String channel, Object data) {
        try (StatefulRedisConnection<String, String> connection = borrowObject()) {
            connection.sync().publish(channel, SerializeUtils.serialize(data));
        }
    }

    @Override
    public void subscribe(Collection<String> channels, IStoreListener listener) {
        StatefulRedisPubSubConnection<String, String> connection = redisClient.connectPubSub();
        connection.addListener(new RedisPubSubListener<String, String>() {

            @Override
            public void message(String channel, String message) {
                log.debug("message={} on channel {}", message, channel);
                listener.notify(channel, message);
            }

            @Override
            public void subscribed(String channel, long count) {
                log.debug("subscribed channel={}, count={}", channel, count);
            }

            @Override
            public void unsubscribed(String channel, long count) {
                log.debug("unsubscribed channel={}, count={}", channel, count);
            }

            @Override
            public void message(String pattern, String channel, String message) {
                log.debug("pattern message={} in channel={}", message, channel);
            }

            @Override
            public void psubscribed(String pattern, long count) {
                log.debug("pattern subscribed pattern={}, count={}", pattern, count);
            }

            @Override
            public void punsubscribed(String pattern, long count) {
                log.debug("pattern unsubscribed channel={}, count={}", pattern, count);
            }

        });
        RedisPubSubAsyncCommands<String, String> redisPubSubAsyncCommands = connection.async();
        subscribed.put(listener, redisPubSubAsyncCommands);
        redisPubSubAsyncCommands.subscribe(channels.toArray(new String[0]));
    }

    @Override
    public void unSubscribe(IStoreListener listener) {
        RedisPubSubAsyncCommands<String, String> commands = subscribed.get(listener);
        if (commands != null) {
            commands.unsubscribe();
            subscribed.remove(listener);
        }
    }

    @Override
    public void destroy() {
        if (null != objectPool) {
            objectPool.close();
        }
        if (null != redisClient) {
            redisClient.shutdown();
        }
    }

}
