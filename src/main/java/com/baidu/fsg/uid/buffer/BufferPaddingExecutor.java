/*
 * Copyright (c) 2017 Baidu, Inc. All Rights Reserve.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.baidu.fsg.uid.buffer;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;

import com.baidu.fsg.uid.utils.NamingThreadFactory;
import com.baidu.fsg.uid.utils.PaddedAtomicLong;

/**
 * Represents an executor for padding {@link RingBuffer}<br>
 * There are two kinds of executors: one for scheduled padding, the other for padding immediately.
 * 
 * @author yutianbao
 */
public class BufferPaddingExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger(RingBuffer.class);

    /** Constants */
    private static final String WORKER_NAME = "RingBuffer-Padding-Worker";
    private static final String SCHEDULE_NAME = "RingBuffer-Padding-Schedule";
    /**
     * 定时填充任务执行的周期，5分钟一次
     */
    private static final long DEFAULT_SCHEDULE_INTERVAL = 5 * 60L; // 5 minutes
    
    /** Whether buffer padding is running */
    private final AtomicBoolean running;

    /**
     * We can borrow UIDs from the future, here store the last second we have consumed
     * 已经消费到的时间(秒)，机器重启时，这个值为当前时间
     */
    private final PaddedAtomicLong lastSecond;

    /** RingBuffer & BufferUidProvider */
    private final RingBuffer ringBuffer;
    private final BufferedUidProvider uidProvider;

    /**
     * Padding immediately by the thread pool
     * 正常填充采用的线程池，当达到填充阈值的时候，会启动该线程池进行填充
     * */
    private final ExecutorService bufferPadExecutors;
    /**
     * Padding schedule thread
     * 定时填充采用的线程池，如果采用这个策略的化，那么会有一个问题，就是很久没有take slot的话，那么消费者后续再take，拿到的slot可能是很久之前生成的
     * 对于某些业务来说，或许会对id有某些特殊的需求，比如需要通过id来推测当前数据产生的时间
     * */
    private final ScheduledExecutorService bufferPadSchedule;
    
    /** Schedule interval Unit as seconds */
    private long scheduleInterval = DEFAULT_SCHEDULE_INTERVAL;

    /**
     * Constructor with {@link RingBuffer} and {@link BufferedUidProvider}, default use schedule
     *
     * @param ringBuffer {@link RingBuffer}
     * @param uidProvider {@link BufferedUidProvider}
     */
    public BufferPaddingExecutor(RingBuffer ringBuffer, BufferedUidProvider uidProvider) {
        this(ringBuffer, uidProvider, true);
    }

    /**
     * Constructor with {@link RingBuffer}, {@link BufferedUidProvider}, and whether use schedule padding
     *
     * @param ringBuffer {@link RingBuffer}
     * @param uidProvider {@link BufferedUidProvider}
     * @param usingSchedule
     */
    public BufferPaddingExecutor(RingBuffer ringBuffer, BufferedUidProvider uidProvider, boolean usingSchedule) {
        this.running = new AtomicBoolean(false);
        this.lastSecond = new PaddedAtomicLong(TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()));
        this.ringBuffer = ringBuffer;
        this.uidProvider = uidProvider;

        // initialize thread pool
        // 初始化填充线程池，机器cpu核心数 * 2
        int cores = Runtime.getRuntime().availableProcessors();
        bufferPadExecutors = Executors.newFixedThreadPool(cores * 2, new NamingThreadFactory(WORKER_NAME));

        // initialize schedule thread
        // 初始化id定时填充线程
        if (usingSchedule) {
            bufferPadSchedule = Executors.newSingleThreadScheduledExecutor(new NamingThreadFactory(SCHEDULE_NAME));
        } else {
            bufferPadSchedule = null;
        }
    }

    /**
     * Start executors such as schedule
     */
    public void start() {
        // 添加定时任务，定时的填充ringBuffer
        if (bufferPadSchedule != null) {
            bufferPadSchedule.scheduleWithFixedDelay(() -> paddingBuffer(), scheduleInterval, scheduleInterval, TimeUnit.SECONDS);
        }
    }

    /**
     * Shutdown executors
     */
    public void shutdown() {
        if (!bufferPadExecutors.isShutdown()) {
            bufferPadExecutors.shutdownNow();
        }

        if (bufferPadSchedule != null && !bufferPadSchedule.isShutdown()) {
            bufferPadSchedule.shutdownNow();
        }
    }

    /**
     * Whether is padding
     *
     * @return
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Padding buffer in the thread pool
     * 异步填充ringBuffer
     */
    public void asyncPadding() {
        bufferPadExecutors.submit(this::paddingBuffer);
    }

    /**
     * Padding buffer fill the slots until to catch the cursor
     * 填充ringBuffer中的slot，直到end指针达到cursor
     */
    public void paddingBuffer() {
        LOGGER.info("Ready to padding buffer lastSecond:{}. {}", lastSecond.get(), ringBuffer);

        // is still running
        if (!running.compareAndSet(false, true)) {
            LOGGER.info("Padding buffer is still running. {}", ringBuffer);
            return;
        }

        // fill the rest slots until to catch the cursor
        boolean isFullRingBuffer = false;
        while (!isFullRingBuffer) {
            // 生成下一秒的id
            List<Long> uidList = uidProvider.provide(lastSecond.incrementAndGet());
            // 填充到ringBuffer中
            for (Long uid : uidList) {
                // 填充ringBuffer
                isFullRingBuffer = !ringBuffer.put(uid);
                if (isFullRingBuffer) {
                    break;
                }
            }
        }

        // not running now
        running.compareAndSet(true, false);
        LOGGER.info("End to padding buffer lastSecond:{}. {}", lastSecond.get(), ringBuffer);
    }

    /**
     * Setters
     */
    public void setScheduleInterval(long scheduleInterval) {
        Assert.isTrue(scheduleInterval > 0, "Schedule interval must positive!");
        this.scheduleInterval = scheduleInterval;
    }
    
}
