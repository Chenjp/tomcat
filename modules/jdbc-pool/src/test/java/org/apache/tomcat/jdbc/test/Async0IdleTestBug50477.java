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
package org.apache.tomcat.jdbc.test;

import java.sql.Connection;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

public class Async0IdleTestBug50477 extends DefaultTestCase {

    @Test
    public void testAsync0Idle0Size() throws Exception {
        System.out.println("[testPoolThreads20Connections10FairAsync] Starting fairness - Tomcat JDBC - Fair - Async");
        this.datasource.getPoolProperties().setMaxActive(10);
        this.datasource.getPoolProperties().setFairQueue(true);
        this.datasource.getPoolProperties().setInitialSize(0);
        try {
            Future<Connection> cf = datasource.getConnectionAsync();
            cf.get(5, TimeUnit.SECONDS);
        }finally {
            tearDown();
        }
    }
}

