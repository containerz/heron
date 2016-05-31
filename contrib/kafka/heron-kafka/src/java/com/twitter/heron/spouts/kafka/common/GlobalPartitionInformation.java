// Copyright 2016 Twitter. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.twitter.heron.spouts.kafka.common;

import java.io.Serializable;
import java.util.*;

import com.google.common.base.Objects;

@SuppressWarnings("serial")
public class GlobalPartitionInformation implements Iterable<Partition>, Serializable {

  private Map<Integer, Broker> partitionMap;
  public String topic;

  public GlobalPartitionInformation(String topic) {
    this.topic = topic;
    this.partitionMap = new TreeMap<>();
  }

  public void addPartition(int partitionId, Broker broker) {
    partitionMap.put(partitionId, broker);
  }

  @Override
  public String toString() {
    return "GlobalPartitionInformation{"
        + "topic=" + topic
        + ", partitionMap=" + partitionMap
        + '}';
  }

  public Broker getBrokerFor(Integer partitionId) {
    return partitionMap.get(partitionId);
  }

  public List<Partition> getOrderedPartitions() {
    List<Partition> partitions = new LinkedList<Partition>();
    for (Map.Entry<Integer, Broker> partition : partitionMap.entrySet()) {
      partitions.add(new Partition(partition.getValue(), this.topic, partition.getKey()));
    }
    return partitions;
  }

  @Override
  public Iterator<Partition> iterator() {
    final Iterator<Map.Entry<Integer, Broker>> iterator = partitionMap.entrySet().iterator();
    final String itTopic = this.topic;
    return new Iterator<Partition>() {
      @Override
      public boolean hasNext() {
        return iterator.hasNext();
      }

      @Override
      public Partition next() {
        Map.Entry<Integer, Broker> next = iterator.next();
        return new Partition(next.getValue(), itTopic, next.getKey());
      }

      @Override
      public void remove() {
        iterator.remove();
      }
    };
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(partitionMap);
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null || getClass() != obj.getClass()) {
      return false;
    }
    final GlobalPartitionInformation other = (GlobalPartitionInformation) obj;
    return Objects.equal(this.partitionMap, other.partitionMap);
  }
}