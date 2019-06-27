package org.micro.neural.limiter.core;

import com.google.common.base.Charsets;
import com.google.common.io.CharStreams;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.micro.neural.config.store.StorePool;
import org.micro.neural.extension.Extension;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * The Cluster Limiter by Redis.
 * <p>
 * 1.Limit instantaneous concurrent
 * 2.Limit the maximum number of requests for a time window
 * 3.Token Bucket
 *
 * @author lry
 **/
@Slf4j
@Extension("cluster")
public class ClusterLimiter extends AbstractCallLimiter {

    private StorePool storePool = StorePool.getInstance();
    public static String CONCURRENT_SCRIPT = loadScript("/script/limiter_concurrent.lua");
    private static String RATE_SCRIPT = loadScript("/script/limiter_rate.lua");
    private static String REQUEST_SCRIPT = loadScript("/script/limiter_request.lua");

    @Override
    protected Acquire incrementConcurrent() {
        List<Object> keys = new ArrayList<>();
        keys.add(limiterConfig.identity());
        keys.add(limiterConfig.getConcurrentPermit());
        keys.add(limiterConfig.getMaxPermitConcurrent());

        try {
            EvalResult evalResult = eval(CONCURRENT_SCRIPT, limiterConfig.getConcurrentTimeout(), keys);
            return evalResult.getCode();
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Acquire.EXCEPTION;
        }
    }

    @Override
    protected void decrementConcurrent() {
        List<Object> keys = new ArrayList<>();
        keys.add(limiterConfig.identity());
        keys.add(-limiterConfig.getConcurrentPermit());
        keys.add(limiterConfig.getMaxPermitConcurrent());

        try {
            EvalResult evalResult = eval(CONCURRENT_SCRIPT, limiterConfig.getConcurrentTimeout(), keys);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    @Override
    protected Acquire tryAcquireRate() {
        List<Object> keys = new ArrayList<>();
        keys.add(limiterConfig.identity());
        keys.add(limiterConfig.getRatePermit());
        keys.add(System.currentTimeMillis());
        keys.add("app");

        try {
            EvalResult evalResult = eval(RATE_SCRIPT, limiterConfig.getRateTimeout(), keys);
            return evalResult.getCode();
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Acquire.EXCEPTION;
        }
    }

    @Override
    protected Acquire tryAcquireRequest() {
        List<Object> keys = new ArrayList<>();
        keys.add(limiterConfig.identity());
        keys.add(limiterConfig.getMaxPermitRequest());
        //TODO
        keys.add(limiterConfig.getRequestInterval().toMillis());

        try {
            EvalResult evalResult = eval(REQUEST_SCRIPT, limiterConfig.getRequestTimeout(), keys);
            return evalResult.getCode();
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Acquire.EXCEPTION;
        }
    }

    private EvalResult eval(String script, Long timeout, List<Object> keys) {
        List<Object> result = storePool.getStore().eval(script, timeout, keys);
        if (result == null || result.size() != 2) {
            return new EvalResult(Acquire.EXCEPTION, 0L);
        }

        Acquire acquire = Acquire.valueOf((int) result.get(0));
        return new EvalResult(acquire, (long) result.get(1));
    }

    private static String loadScript(String name) {
        try {
            return CharStreams.toString(new InputStreamReader(
                    ClusterLimiter.class.getResourceAsStream(name), Charsets.UTF_8));
        } catch (IOException e) {
            log.error(e.getMessage(), e);
            return null;
        }
    }

    @Data
    @ToString
    @NoArgsConstructor
    @AllArgsConstructor
    private static class EvalResult implements Serializable {

        private static final long serialVersionUID = 965512125433109743L;

        private Acquire code;
        private Long num;

    }

}
