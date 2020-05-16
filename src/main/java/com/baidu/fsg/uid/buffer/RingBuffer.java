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

import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;

import com.baidu.fsg.uid.utils.PaddedAtomicLong;

/**
 * Represents a ring buffer based on array.<br>
 * Using array could improve read element performance due to the CUP cache line. To prevent 
 * the side effect of False Sharing, {@link PaddedAtomicLong} is using on 'tail' and 'cursor'<p>
 * 
 * A ring buffer is consisted of:
 * <li><b>slots:</b> each element of the array is a slot, which is be set with a UID
 * <li><b>flags:</b> flag array corresponding the same index with the slots, indicates whether can take or put slot
 * <li><b>tail:</b> a sequence of the max slot position to produce 
 * <li><b>cursor:</b> a sequence of the min slot position to consume
 * 
 * @author yutianbao
 */
public class RingBuffer {
    private static final Logger LOGGER = LoggerFactory.getLogger(RingBuffer.class);

    /** Constants */
    private static final int START_POINT = -1;
    private static final long CAN_PUT_FLAG = 0L;
    private static final long CAN_TAKE_FLAG = 1L;
    public static final int DEFAULT_PADDING_PERCENT = 50;

    /** The size of RingBuffer's slots, each slot hold a UID */
    private final int bufferSize;
    private final long indexMask;
    private final long[] slots;
    /**
     * 标志数组，数组大小与slots一致，记录是否可以添加到slots中，防止多线程添加id时带来的重复添加问题
     */
    private final PaddedAtomicLong[] flags;

    /** Tail: last position sequence to produce */
    private final AtomicLong tail = new PaddedAtomicLong(START_POINT);

    /** Cursor: current position sequence to consume */
    private final AtomicLong cursor = new PaddedAtomicLong(START_POINT);

    /**
     * Threshold for trigger padding buffer
     * 填充因子，少于这个阈值的时候，会预填充ringBuffer
     * 默认是50%
     **/
    private final int paddingThreshold; 
    
    /**
     * Reject put/take buffer handle policy
     * 拒绝策略
     * 默认的策略
     *      put操作：直接丢弃，打一条日志
     *      take操作：报个错
     * */
    private RejectedPutBufferHandler rejectedPutHandler = this::discardPutBuffer;
    private RejectedTakeBufferHandler rejectedTakeHandler = this::exceptionRejectedTakeBuffer; 
    
    /**
     * Executor of padding buffer
     * id填充线程池
     *
     **/
    private BufferPaddingExecutor bufferPaddingExecutor;

    /**
     * Constructor with buffer size, paddingFactor default as {@value #DEFAULT_PADDING_PERCENT}
     * 
     * @param bufferSize must be positive & a power of 2
     */
    public RingBuffer(int bufferSize) {
        this(bufferSize, DEFAULT_PADDING_PERCENT);
    }
    
    /**
     * Constructor with buffer size & padding factor
     * 
     * @param bufferSize must be positive & a power of 2
     * @param paddingFactor percent in (0 - 100). When the count of rest available UIDs reach the threshold, it will trigger padding buffer<br>
     *        Sample: paddingFactor=20, bufferSize=1000 -> threshold=1000 * 20 /100,  
     *        padding buffer will be triggered when tail-cursor<threshold
     */
    public RingBuffer(int bufferSize, int paddingFactor) {
        // check buffer size is positive & a power of 2; padding factor in (0, 100)
        Assert.isTrue(bufferSize > 0L, "RingBuffer size must be positive");
        Assert.isTrue(Integer.bitCount(bufferSize) == 1, "RingBuffer size must be a power of 2");
        Assert.isTrue(paddingFactor > 0 && paddingFactor < 100, "RingBuffer size must be positive");

        this.bufferSize = bufferSize;
        this.indexMask = bufferSize - 1;
        this.slots = new long[bufferSize];
        this.flags = initFlags(bufferSize);
        
        this.paddingThreshold = bufferSize * paddingFactor / 100;
    }

    /**
     * Put an UID in the ring & tail moved<br>
     * We use 'synchronized' to guarantee the UID fill in slot & publish new tail sequence as atomic operations<br>
     * 
     * <b>Note that: </b> It is recommended to put UID in a serialize way, cause we once batch generate a series UIDs and put
     * the one by one into the buffer, so it is unnecessary put in multi-threads
     *
     * @param uid
     * @return false means that the buffer is full, apply {@link RejectedPutBufferHandler}
     *
     * 方法采用了synchronized，填充ringBuffer是线程安全的
     */
    public synchronized boolean put(long uid) {
        long currentTail = tail.get();
        long currentCursor = cursor.get();

        // tail catches the cursor, means that you can't put any cause of RingBuffer is full
        // 计算剩余的slot的数量
        long distance = currentTail - (currentCursor == START_POINT ? 0 : currentCursor);
        // 如果已满
        if (distance == bufferSize - 1) {
            rejectedPutHandler.rejectPutBuffer(this, uid);
            return false;
        }

        // 1. pre-check whether the flag is CAN_PUT_FLAG
        // 计算在ringBuffer中的索引，其实就是对数组大小取余，不过这里的数组大小都是2的整数倍，所以可以 & (arr.length -1)，速度更快
        int nextTailIndex = calSlotIndex(currentTail + 1);
        // 检查标志位，其实我觉得不需要这一步也行，因为既然方法都加synchronized了，那么都是串形填充slot了，别的线程不可能超过自己
        // 前面都判断了未满，那么这里肯定就不可能是CAN_TAKE_FLAG了，只可能是CAN_PUT_FLAG
        // 所以我觉得这一步可以省略。。。
        if (flags[nextTailIndex].get() != CAN_PUT_FLAG) {
            rejectedPutHandler.rejectPutBuffer(this, uid);
            return false;
        }

        // 2. put UID in the next slot
        // 3. update next slot' flag to CAN_TAKE_FLAG
        // 4. publish tail with sequence increase by one
        // 填充slot，同时设置对应的标志位为CAN_TAKE_FLAG，tail索引自增
        // 因为这个方法是synchronized，所以这对三个值的更新是满足一致性的
        // 同时也保证了刚刚放入的slot无法立即被消费，直到tail.incrementAndGet()被执行
        slots[nextTailIndex] = uid;
        flags[nextTailIndex].set(CAN_TAKE_FLAG);
        tail.incrementAndGet();

        // The atomicity of operations above, guarantees by 'synchronized'. In another word,
        // the take operation can't consume the UID we just put, until the tail is published(tail.incrementAndGet())
        //
        return true;
    }

    /**
     * Take an UID of the ring at the next cursor, this is a lock free operation by using atomic cursor<p>
     * 
     * Before getting the UID, we also check whether reach the padding threshold, 
     * the padding buffer operation will be triggered in another thread<br>
     * If there is no more available UID to be taken, the specified {@link RejectedTakeBufferHandler} will be applied<br>
     * 
     * @return UID
     * @throws IllegalStateException if the cursor moved back
     */
    public long take() {
        // spin get next available cursor
        long currentCursor = cursor.get();
        // 获取下一个游标，如果当前游标==tail，那么返回当前游标
        long nextCursor = cursor.updateAndGet(old -> old == tail.get() ? old : old + 1);

        // check for safety consideration, it never occurs
        Assert.isTrue(nextCursor >= currentCursor, "Curosr can't move back");

        // trigger padding in an async-mode if reach the threshold
        long currentTail = tail.get();
        // 判断剩下的slot数量是否小于阈值来决定是否需要预生成填充id
        if (currentTail - nextCursor < paddingThreshold) {
            LOGGER.info("Reach the padding threshold:{}. tail:{}, cursor:{}, rest:{}", paddingThreshold, currentTail,
                    nextCursor, currentTail - nextCursor);
            bufferPaddingExecutor.asyncPadding();
        }

        // cursor catch the tail, means that there is no more available UID to take
        // 如果当前游标==tail，那么采用拒绝策略
        // 默认的拒绝策略是报错0.0,我们可以在业务中采用自定义的策略，做一些其他的操作
        if (nextCursor == currentCursor) {
            rejectedTakeHandler.rejectTakeBuffer(this);
        }

        // 1. check next slot flag is CAN_TAKE_FLAG
        // 检查对应的标志符是否是可取
        int nextCursorIndex = calSlotIndex(nextCursor);
        Assert.isTrue(flags[nextCursorIndex].get() == CAN_TAKE_FLAG, "Curosr not in can take status");

        // 2. get UID from next slot
        // 3. set next slot flag as CAN_PUT_FLAG.
        // 设置标志位为CAN_PUT_FLAG，即表示可填充了，代表已经被取走了
        long uid = slots[nextCursorIndex];
        flags[nextCursorIndex].set(CAN_PUT_FLAG);

        // Note that: Step 2,3 can not swap. If we set flag before get value of slot, the producer may overwrite the
        // slot with a new UID, and this may cause the consumer take the UID twice after walk a round the ring
        // 第二步和第三步不能交换，如果我们先设置标志符，生产者（填充线程）可能就直接填了，那么拿到的id是刚刚添加的新id，那么在未来的某个时间，这个id会被再消费一次，也就意味着id被消费了两次
        return uid;
    }

    /**
     * Calculate slot index with the slot sequence (sequence % bufferSize) 
     */
    protected int calSlotIndex(long sequence) {
        return (int) (sequence & indexMask);
    }

    /**
     * Discard policy for {@link RejectedPutBufferHandler}, we just do logging
     */
    protected void discardPutBuffer(RingBuffer ringBuffer, long uid) {
        LOGGER.warn("Rejected putting buffer for uid:{}. {}", uid, ringBuffer);
    }
    
    /**
     * Policy for {@link RejectedTakeBufferHandler}, throws {@link RuntimeException} after logging 
     */
    protected void exceptionRejectedTakeBuffer(RingBuffer ringBuffer) {
        LOGGER.warn("Rejected take buffer. {}", ringBuffer);
        throw new RuntimeException("Rejected take buffer. " + ringBuffer);
    }
    
    /**
     * Initialize flags as CAN_PUT_FLAG
     */
    private PaddedAtomicLong[] initFlags(int bufferSize) {
        PaddedAtomicLong[] flags = new PaddedAtomicLong[bufferSize];
        for (int i = 0; i < bufferSize; i++) {
            flags[i] = new PaddedAtomicLong(CAN_PUT_FLAG);
        }
        
        return flags;
    }

    /**
     * Getters
     */
    public long getTail() {
        return tail.get();
    }

    public long getCursor() {
        return cursor.get();
    }

    public int getBufferSize() {
        return bufferSize;
    }

    /**
     * Setters
     */
    public void setBufferPaddingExecutor(BufferPaddingExecutor bufferPaddingExecutor) {
        this.bufferPaddingExecutor = bufferPaddingExecutor;
    }

    public void setRejectedPutHandler(RejectedPutBufferHandler rejectedPutHandler) {
        this.rejectedPutHandler = rejectedPutHandler;
    }

    public void setRejectedTakeHandler(RejectedTakeBufferHandler rejectedTakeHandler) {
        this.rejectedTakeHandler = rejectedTakeHandler;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("RingBuffer [bufferSize=").append(bufferSize)
               .append(", tail=").append(tail)
               .append(", cursor=").append(cursor)
               .append(", paddingThreshold=").append(paddingThreshold).append("]");
        
        return builder.toString();
    }

}
