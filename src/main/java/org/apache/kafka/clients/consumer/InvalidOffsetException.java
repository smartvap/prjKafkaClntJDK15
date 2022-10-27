/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the NOTICE
 * file distributed with this work for additional information regarding copyright ownership. The ASF licenses this file
 * to You under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.apache.kafka.clients.consumer;

import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;

import java.util.Set;

/**
 * Thrown when the offset for a set of partitions is invalid (either undefined or out of range),
 * and no reset policy has been configured.
 * @see NoOffsetForPartitionException
 * @see OffsetOutOfRangeException
 */
public abstract class InvalidOffsetException extends KafkaException {

    private static final long serialVersionUID = -3321535232432314185L;

	public InvalidOffsetException(String message) {
        super(message);
    }

    public abstract Set<TopicPartition> partitions();

}
