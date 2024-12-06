/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.catalina.util;

import java.util.concurrent.ScheduledExecutorService;

/**
 * A RateLimiter that compromises accuracy for speed in order to provide maximum throughput.
 */
public class FastRateLimiter extends RateLimiterBase implements RateLimiter {

    @Override
    protected String getDefaultPolicyName() {
        return "fast";
    }

    @Override
    protected TimeBucketCounter newCounterInstance(ScheduledExecutorService executorService, int duration) {
        return new FastTimeBucketCounter(executorService, duration);
    }

    /**
     * A fast counter that optimizes efficiency at the expense of approximate bucket indexing.
     */
    class FastTimeBucketCounter extends TimeBucketCounter {

        FastTimeBucketCounter(ScheduledExecutorService utilityExecutor, int bucketDuration) {
            super(utilityExecutor, getActualDuration(bucketDuration));
            this.numBits = determineShiftBitsOfDuration(bucketDuration);
            this.ratio = ratioToPowerOf2(bucketDuration * 1000);
        }

        /**
         * Milliseconds bucket size as a Power of 2 for bit shift math, e.g. 16 for 65_536ms which is about 1:05 minute
         */
        private final int numBits;

        /**
         * Ratio of actual duration to config duration
         */
        private final double ratio;

        @Override
        public long getBucketIndex(long timestamp) {
            return System.currentTimeMillis() >> this.numBits;
        }

        protected int getNumBits() {
            return numBits;
        }

        /**
         * Determines the bits of shift for the specific bucket duration in seconds, which used to figure out the
         * correct bucket index.
         */
        protected static final int determineShiftBitsOfDuration(int duration) {
            int bits = 0;
            int pof2 = nextPowerOf2(duration * 1000);
            int bitCheck = pof2;
            while (bitCheck > 1) {
                bitCheck = pof2 >> ++bits;
            }
            return bits;
        }

        /**
         * The actual duration may differ from the configured duration because it is set to the next power of 2 value in
         * order to perform very fast bit shift arithmetic.
         *
         * @param duration in seconds
         *
         * @return the actual bucket duration in seconds
         *
         * @see FastTimeBucketCounter#determineShiftBitsOfDuration(int)
         */
        protected static final int getActualDuration(int duration) {
            return (int) (1L << determineShiftBitsOfDuration(duration)) / 1000;
        }


        /**
         * Returns the ratio between the configured duration param and the actual duration which will be set to the next
         * power of 2. We then multiply the configured requests param by the same ratio in order to compensate for the
         * added time, if any.
         *
         * @return the ratio, e.g. 1.092 if the actual duration is 65_536 for the configured duration of 60_000
         */
        public double getRatio() {
            return ratio;
        }

        /**
         * Returns the ratio to the next power of 2 so that we can adjust the value.
         */
        protected static double ratioToPowerOf2(int value) {
            double nextPO2 = nextPowerOf2(value);
            return Math.round((1000d * nextPO2 / value)) / 1000d;
        }

        /**
         * Returns the next power of 2 given a value, e.g. 256 for 250, or 1024, for 1000.
         */
        static int nextPowerOf2(int value) {
            int valueOfHighestBit = Integer.highestOneBit(value);
            if (valueOfHighestBit == value) {
                return value;
            }

            return valueOfHighestBit << 1;
        }


        @Override
        public long getMillisUntilNextBucket() {
            long millis = System.currentTimeMillis();
            long nextTimeBucketMillis = ((millis + (long) Math.pow(2, numBits)) >> numBits) << numBits;
            long delta = nextTimeBucketMillis - millis;
            return delta;
        }
    }
}
