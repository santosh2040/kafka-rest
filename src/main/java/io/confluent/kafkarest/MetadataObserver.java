/**
 * Copyright 2014 Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.confluent.kafkarest;

import io.confluent.kafkarest.entities.Partition;
import io.confluent.kafkarest.entities.PartitionReplica;
import io.confluent.kafkarest.entities.Topic;
import kafka.api.LeaderAndIsr;
import kafka.cluster.Broker;
import kafka.utils.ZKStringSerializer$;
import kafka.utils.ZkUtils;
import org.I0Itec.zkclient.ZkClient;
import scala.collection.*;
import scala.collection.Map;
import scala.math.Ordering;

import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.NotFoundException;
import java.util.*;
import java.util.Set;

/**
 * Observes metadata about the Kafka cluster.
 */
public class MetadataObserver {
    private ZkClient zkClient;

    public MetadataObserver(Config config) {
        zkClient = new ZkClient(config.zookeeperConnect, 30000, 30000, ZKStringSerializer$.MODULE$);
    }

    public List<Integer> getBrokerIds() {
        Seq<Broker> brokers = ZkUtils.getAllBrokersInCluster(zkClient);
        List<Integer> brokerIds = new Vector<Integer>(brokers.size());
        for (Broker broker : JavaConversions.asJavaCollection(brokers)) {
            brokerIds.add(broker.id());
        }
        return brokerIds;
    }

    public List<Topic> getTopics() {
        try {
            Seq<String> topicNames = ZkUtils.getAllTopics(zkClient).sorted(Ordering.String$.MODULE$);
            return getTopicsData(topicNames);
        }
        catch(NotFoundException e) {
            throw new InternalServerErrorException(e);
        }
    }

    public boolean topicExists(String topicName) {
        List<Topic> topics = getTopics();
        for(Topic topic : topics) {
            if (topic.getName().equals(topicName)) return true;
        }
        return false;
    }

    public Topic getTopic(String topicName) {
        return getTopicsData(JavaConversions.asScalaIterable(Arrays.asList(topicName)).toSeq()).get(0);
    }

    private List<Topic> getTopicsData(Seq<String> topicNames) {
        Map<String, Map<Object, Seq<Object>>> topicPartitions = ZkUtils.getPartitionAssignmentForTopics(zkClient, topicNames);
        List<Topic> topics = new Vector<Topic>(topicNames.size());
        for(String topicName : JavaConversions.asJavaCollection(topicNames)) {
            Map<Object, Seq<Object>> partitionMap = topicPartitions.get(topicName).get();
            int numPartitions = partitionMap.size();
            if (numPartitions == 0)
                throw new NotFoundException();
            Topic topic = new Topic(topicName, numPartitions);
            topics.add(topic);
        }
        return topics;
    }

    public List<Partition> getTopicPartitions(String topic) {
        return getTopicPartitions(topic, null);
    }

    public Partition getTopicPartition(String topic, int partition) {
        List<Partition> partitions = getTopicPartitions(topic, partition);
        if (partitions.isEmpty()) return null;
        return partitions.get(0);
    }

    private List<Partition> getTopicPartitions(String topic, Integer partitions_filter) {
        List<Partition> partitions = new Vector<>();
        Map<String, Map<Object, Seq<Object>>> topicPartitions = ZkUtils.getPartitionAssignmentForTopics(
                zkClient, JavaConversions.asScalaIterable(Arrays.asList(topic)).toSeq());
        Map<Object, Seq<Object>> parts = topicPartitions.get(topic).get();
        for(java.util.Map.Entry<Object,Seq<Object>> part : JavaConversions.asJavaMap(parts).entrySet()) {
            int partId = (int)part.getKey();
            if (partitions_filter != null && partitions_filter != partId)
                continue;

            Partition p = new Partition();
            p.setPartition(partId);
            LeaderAndIsr leaderAndIsr = ZkUtils.getLeaderAndIsrForPartition(zkClient, topic, partId).get();
            p.setLeader(leaderAndIsr.leader());
            scala.collection.immutable.Set<Integer> isr = leaderAndIsr.isr().toSet();
            List<PartitionReplica> partReplicas = new Vector<>();
            for(Object brokerObj : JavaConversions.asJavaCollection(part.getValue())) {
                int broker = (int)brokerObj;
                PartitionReplica r = new PartitionReplica(broker, (leaderAndIsr.leader() == broker), isr.contains(broker));
                partReplicas.add(r);
            }
            p.setReplicas(partReplicas);
            partitions.add(p);
        }
        return partitions;
    }
}