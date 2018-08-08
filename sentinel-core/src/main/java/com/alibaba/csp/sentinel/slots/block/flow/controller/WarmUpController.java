/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.csp.sentinel.slots.block.flow.controller;

import java.util.concurrent.atomic.AtomicLong;

import com.alibaba.csp.sentinel.util.TimeUtil;
import com.alibaba.csp.sentinel.node.Node;
import com.alibaba.csp.sentinel.slots.block.flow.Controller;

/**
 * The principle idea comes from guava. However, the calculation of guava is
 * rate-based, which means that we need to translate rate to qps.
 * 此限流方法，来源于guava。然而，guava的计算是基于评级的，这意味着我们需要将速率转换为qps。
 *
 * https://github.com/google/guava/blob/master/guava/src/com/google/common/util/concurrent/SmoothRateLimiter.java
 *
 * Requests arriving at the pulse may drag down long idle systems even though it
 * has a much larger handling capability in stable period. It usually happens in
 * scenarios that require extra time for initialization, for example, db
 * establishes a connection; connects to a remote service, and so on.
 *
 * 到达的请求，可能会拖慢长时间空闲的系统，即使系统在稳定时期有更大的处理能力。
 * 它通常发生在需要额外时间进行初始化的场景中，例如，db建立一个连接;连接到远程服务，等等。
 *
 * That’s why we need “warm up”.
 * 这就是为什么需要热身。
 *
 * Sentinel’s “warm up” implementation is based on Guava's algorithm. However,
 * unlike Guava's scenario, which is a “leaky bucket”, and is mainly used to
 * adjust the request interval, Sentinel is more focus on controlling the count
 * of incoming requests per second without calculating its interval.
 * Sentinel的热身实现基于guava的algorithm。然而，与Guava的场景不同，Guava的实现是一个“漏桶”，
 * 主要用于调整请求间隔，而Sentinel则更关注于控制每秒传入请求的数量，而不计算它的间隔。
 *
 * Sentinel's "warm-up" implementation is based on the guava-based algorithm.
 * However, Guava’s implementation focus on adjusting the request interval, in
 * other words, a Leaky bucket. Sentinel pays more attention to controlling the
 * count of incoming requests per second without calculating its interval, it is
 * more like a “Token bucket.”
 * Sentinel的“预热”实现是基于guava-based算法的。然而，Guava的实现重点是调整请求间隔， 换句话说，一个漏水的桶。
 * Sentinel更注重控制在不计算其间隔的情况下，每秒钟输入请求数，它是 更像是一个“令牌桶”。
 *
 *
 * The remaining tokens in the bucket is used to measure the system utility.
 * Suppose a system can handle b requests per second. Every second b tokens will
 * be added into the bucket until the bucket is full. And when system processes
 * a request, it takes a token from the bucket. The more tokens left in the
 * bucket, the lower the utilization of the system; when the token in the token
 * bucket is above a certain threshold, we call it in a "saturation" state.
 * 桶中的剩余token用于测量系统状态。假设一个系统可以每秒处理b请求。每秒有b个token放入桶中直到桶满了。
 * 当系统处理一个请求，它从桶中拿一个token。从桶中拿走的token越多，系统的可用性越低。
 * 当令牌桶中的令牌高于某个阈值时，我们将其称为“饱和”状态。
 *
 *
 * Base on Guava’s theory, there is a linear equation we can write this in the
 * form y = m * x + b where y (a.k.a y(x)), or qps(q)), is our expected QPS
 * given a saturated period (e.g. 3 minutes in), m is the rate of change from
 * our cold (minimum) rate to our stable (maximum) rate, x (or q) is the
 * occupied token.
 * 基于guava的理论，有一个线性方程可以描述这一情况：
 * form y = m * x + b where y (也叫作 y(x)), or qps(q)) ；y就是qps
 * 给定一个饱和期（比如3分钟），m是我们从最小速率到稳定速率的变化率，x是被占用的令牌
 *
 * @author jialiang.linjl
 */
public class WarmUpController implements Controller {

    private double count;
    private int coldFactor;
    private int warningToken = 0;
    private int maxToken;
    private double slope;

    private AtomicLong storedTokens = new AtomicLong(0);
    private AtomicLong lastFilledTime = new AtomicLong(0);

    public WarmUpController(double count, int warmupPeriodInSec, int coldFactor) {
        construct(count, warmupPeriodInSec, coldFactor);
    }

    public WarmUpController(double count, int warmUpPeriodInMic) {
        construct(count, warmUpPeriodInMic, 3);
    }

    private void construct(double count, int warmUpPeriodInSec, int coldFactor) {

        if (coldFactor <= 1) {
            throw new IllegalArgumentException("Cold factor should be larger than 1");
        }

        this.count = count;

        this.coldFactor = coldFactor;

        // thresholdPermits = 0.5 * warmupPeriod / stableInterval.
        // warningToken = 100;
        warningToken = (int)(warmUpPeriodInSec * count) / (coldFactor - 1);
        // / maxPermits = thresholdPermits + 2 * warmupPeriod /
        // (stableInterval + coldInterval)
        // maxToken = 200
        maxToken = warningToken + (int)(2 * warmUpPeriodInSec * count / (1.0 + coldFactor));

        // slope
        // slope = (coldIntervalMicros - stableIntervalMicros) / (maxPermits
        // - thresholdPermits);
        slope = (coldFactor - 1.0) / count / (maxToken - warningToken);

    }

    @Override
    public boolean canPass(Node node, int acquireCount) {
        long passQps = node.passQps(); // 算上本次请求的qps

        long previousQps = node.previousPassQps(); // 之前的qps
        syncToken(previousQps);// 同步令牌桶

        // dubbo：开始计算它的斜率
        // dubbo：如果进入了警戒线，开始调整他的qps
        long restToken = storedTokens.get();
        if (restToken >= warningToken) {
            long aboveToken = restToken - warningToken;
            // dubbo：消耗的速度要比warning快，但是要比慢
            // dubbo：current interval = restToken*slope+1/count
            double warningQps = Math.nextUp(1.0 / (aboveToken * slope + 1.0 / count));
            if (passQps + acquireCount <= warningQps) {
                return true;
            }
        } else {
            if (passQps + acquireCount <= count) {
                return true;
            }
        }

        return false;
    }

    private void syncToken(long passQps) {
        long currentTime = TimeUtil.currentTimeMillis();
        currentTime = currentTime - currentTime % 1000;
        long oldLastFillTime = lastFilledTime.get();
        if (currentTime <= oldLastFillTime) {
            return;
        }

        long oldValue = storedTokens.get();
        long newValue = coolDownTokens(currentTime, passQps);

        if (storedTokens.compareAndSet(oldValue, newValue)) {
            long currentValue = storedTokens.addAndGet(0 - passQps);
            if (currentValue < 0) {
                storedTokens.set(0L);
            }
            lastFilledTime.set(currentTime);
        }

    }

    private long coolDownTokens(long currentTime, long passQps) {
        long oldValue = storedTokens.get();
        long newValue = oldValue;

        // 添加令牌的判断前提条件:
        // 当令牌的消耗程度远远低于警戒线的时候
        if (oldValue < warningToken) {
            newValue = (long)(oldValue + (currentTime - lastFilledTime.get()) * count / 1000);
        } else if (oldValue > warningToken) {
            if (passQps < (int)count / coldFactor) {
                newValue = (long)(oldValue + (currentTime - lastFilledTime.get()) * count / 1000);
            }
        }
        return Math.min(newValue, maxToken);
    }

}
