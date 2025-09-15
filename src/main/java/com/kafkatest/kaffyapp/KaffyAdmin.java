package com.kafkatest.kaffyapp;

import com.kafkatest.kaffyapp.dto.*;

import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.header.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

@Service
public class KaffyAdmin {
    private static final Logger logger = LoggerFactory.getLogger(KaffyAdmin.class);

    @Autowired
    private AdminClient adminClient;

    @Autowired
    private Properties consumerProperties;

    @Autowired
    private KafkaProducer<String, String> kafkaProducer;



    public ClusterDescription getClusterDescription() throws ExecutionException, InterruptedException {
        DescribeClusterResult clusterResult = adminClient.describeCluster();
        Collection<Node> node = clusterResult.nodes().get();
        Node controller =clusterResult.controller().get();
        String clusterId = clusterResult.clusterId().get();

        return new ClusterDescription(
                clusterId,
                node,
                controller
        );

    }

    public Set<String > getAllTopics() throws ExecutionException, InterruptedException {
        Set<String> listOfTopics;
        ListTopicsResult topicsResult = adminClient.listTopics();
        listOfTopics = topicsResult.names().get();

        return listOfTopics;

    }

    public List<InternalTopicsInfo> getTopicListing() {
        List<InternalTopicsInfo> internalTopics = new ArrayList<>();
        logger.info("Listing internal topics");
        try {
            ListTopicsOptions listTopicsOptions = new ListTopicsOptions().listInternal(true);
            ListTopicsResult topicsResult = adminClient.listTopics(listTopicsOptions);
            Collection<TopicListing> topicListings = topicsResult.listings().get();
            internalTopics = topicListings.stream()
                    .filter(TopicListing::isInternal)
                    .map(tpList -> new InternalTopicsInfo(
                            tpList.topicId(),
                            tpList.name()
                    ))
                    .collect(Collectors.toList());
            logger.info("Found {} internal topics", internalTopics.size());
        } catch (InterruptedException | ExecutionException e) {
            logger.error("Error listing topics", e);
        }
        return internalTopics;
    }

    public List<TopicInfo> getTopics(String searchFilter) {
        try {

            Set<String> topicNames = getAllTopics();
            // Apply search filter
            if (searchFilter != null && !searchFilter.trim().isEmpty()) {
                topicNames = topicNames.stream()
                        .filter(name -> name.toLowerCase().contains(searchFilter.toLowerCase()))
                        .collect(Collectors.toSet());
            }

            if (topicNames.isEmpty()) {
                return Collections.emptyList();
            }

            DescribeTopicsResult topicsDescResult = adminClient.describeTopics(topicNames);
            Map<String, TopicDescription> topicDescriptions = topicsDescResult.allTopicNames().get();

            // Get topic configurations to check for custom configs
            List<ConfigResource> configResources = topicNames.stream()
                    .map(name -> new ConfigResource(ConfigResource.Type.TOPIC, name))
                    .collect(Collectors.toList());

            DescribeConfigsResult configsResult = adminClient.describeConfigs(configResources);
            Map<ConfigResource, Config> configs = configsResult.all().get();

            return topicDescriptions.entrySet().stream().map(entry -> {
                String topicName = entry.getKey();
                TopicDescription topicDesc = entry.getValue();

                int partitionCount = topicDesc.partitions().size();
                int preferredLeaders = 0;
                int underReplicatedPartitions = 0;

                for (TopicPartitionInfo partitionInfo : topicDesc.partitions()) {
                    if (partitionInfo.leader() != null &&
                            !partitionInfo.replicas().isEmpty() &&
                            partitionInfo.leader().equals(partitionInfo.replicas().get(0))) {
                        preferredLeaders++;
                    }
                    if (partitionInfo.isr().size() < partitionInfo.replicas().size()) {
                        underReplicatedPartitions++;
                    }
                }

                double preferredPercentage = partitionCount > 0 ?
                        (preferredLeaders * 100.0) / partitionCount : 100.0;

                // Check for custom configuration
                ConfigResource configResource = new ConfigResource(ConfigResource.Type.TOPIC, topicName);
                boolean hasCustomConfig = configs.containsKey(configResource) &&
                        configs.get(configResource).entries().stream()
                                .anyMatch(configEntry -> !configEntry.isDefault());

                return new TopicInfo(
                        topicName,
                        partitionCount,
                        preferredPercentage,
                        underReplicatedPartitions,
                        hasCustomConfig
                );
            }).collect(Collectors.toList());

        } catch (InterruptedException | ExecutionException e) {
            logger.error("Error getting topics", e);
            return Collections.emptyList();
        }
    }

    public void createTopic(CreateTopicRequest request) throws ExecutionException, InterruptedException {

        Set<String> listOfTopics = getAllTopics();
        if(listOfTopics != null && !listOfTopics.isEmpty() && listOfTopics.contains(request.getTopicName())) {
            return;
        }
        NewTopic newTopic = new NewTopic(request.getTopicName(), request.getNoOfPartitions(), request.getReplicas());
        CreateTopicsResult results = adminClient.createTopics(List.of(newTopic));
        results.all().get();
    }

    public ClusterInfo getClusterInfo () throws ExecutionException, InterruptedException {
        Set<String> listOfTopics = getAllTopics();
        int topicsCount = 0;
        if (listOfTopics != null && !listOfTopics.isEmpty()) {
            topicsCount = listOfTopics.size();
        }
        DescribeTopicsResult topicsResult = adminClient.describeTopics(listOfTopics);

        Map<String, TopicDescription> topicDescriptionMap = topicsResult.allTopicNames().get();

        int partitionCount = topicDescriptionMap
                .values().stream()
                .mapToInt(topDes -> topDes.partitions().size())
                .sum();


        int preferredLeaders = 0;
        int underReplicatedPartitions = 0;
        int totalPartitionCount = 0;

        for (TopicDescription topicDesc : topicDescriptionMap.values()) {
            for (TopicPartitionInfo partitionInfo : topicDesc.partitions()) {
                totalPartitionCount++;
                if (partitionInfo.leader() != null &&
                        !partitionInfo.replicas().isEmpty() &&
                        partitionInfo.leader().equals(partitionInfo.replicas().get(0))) {
                    preferredLeaders++;
                }
                if (partitionInfo.isr().size() < partitionInfo.replicas().size()) {
                    underReplicatedPartitions++;
                }
            }
        }

        double preferredLeaderPercentage = totalPartitionCount > 0 ?
                (preferredLeaders * 100.0) / totalPartitionCount : 100.0;


        // Get controller information
        DescribeClusterResult clusterResult = adminClient.describeCluster();
        Node controllerNode = clusterResult.controller().get();
        Controller controller = new Controller(controllerNode.id(), controllerNode.host(), controllerNode.port());

        return new ClusterInfo("kafka-cluster", "localHost:9092", topicsCount, partitionCount, preferredLeaderPercentage, underReplicatedPartitions, controller);


    }


    public List<BrokerInfo> getBrokers() {
        try {
            ClusterDescription clusterDescription = getClusterDescription();

            Collection<Node> nodes = clusterDescription.getNodes();
            Node controller = clusterDescription.getController();

            // Get partition distribution
            Map<Integer, Integer> brokerPartitionCount = getBrokerPartitionCount();
            int totalPartitions = brokerPartitionCount.values().stream().mapToInt(Integer::intValue).sum();

            return nodes.stream().map(node -> {
                int partitionCount = brokerPartitionCount.getOrDefault(node.id(), 0);
                double partitionPercentage = totalPartitions > 0 ?
                        (partitionCount * 100.0) / totalPartitions : 0.0;

                return new BrokerInfo(
                        node.id(),
                        node.host(),
                        node.port(),
                        node.rack(),
                        controller != null && controller.id() == node.id(),
                        partitionCount,
                        partitionPercentage
                );
            }).collect(Collectors.toList());

        } catch (InterruptedException | ExecutionException e) {
            logger.error("Error getting brokers", e);
            return Collections.emptyList();
        }
    }

    private Map<Integer, Integer> getBrokerPartitionCount() {
        try {

            Set<String> topicNames = getAllTopics();

            DescribeTopicsResult topicsDescResult = adminClient.describeTopics(topicNames);
            Map<String, TopicDescription> topicDescriptions = topicsDescResult.allTopicNames().get();

            Map<Integer, Integer> brokerPartitionCount = new HashMap<>();

            for (TopicDescription topicDesc : topicDescriptions.values()) {
                for (TopicPartitionInfo partitionInfo : topicDesc.partitions()) {
                    for (Node replica : partitionInfo.replicas()) {
                        brokerPartitionCount.merge(replica.id(), 1, Integer::sum);
                    }
                }
            }

            return brokerPartitionCount;

        } catch (InterruptedException | ExecutionException e) {
            logger.error("Error getting broker partition count", e);
            return Collections.emptyMap();
        }
    }

    public TopicDetails getTopicDetails(String topicName) {
        try {
            // Fetch topic description
            TopicDescription topicDescription = adminClient
                    .describeTopics(List.of(topicName))
                    .allTopicNames()
                    .get()
                    .get(topicName);

            int partitionCount = topicDescription.partitions().size();
            int replicationFactor = partitionCount == 0 ? 0 : topicDescription.partitions().get(0).replicas().size();

            // Fetch topic configuration
            ConfigResource configResource = new ConfigResource(ConfigResource.Type.TOPIC, topicName);
            Config config = adminClient
                    .describeConfigs(Collections.singleton(configResource))
                    .all()
                    .get()
                    .get(configResource);

            Map<String, String> configurations = new HashMap<>();
            if (config != null) {
                config.entries().stream()
                        .filter(entry -> !entry.isDefault())
                        .forEach(entry -> configurations.put(entry.name(), entry.value()));
            }
            logger.info("Configuration retrieved for topic: {}, custom configs: {}", topicName, configurations.size());

            // Build partition details
            List<PartitionDetails> partitions = topicDescription.partitions().stream().map(partitionInfo -> {
                List<Integer> replicas = partitionInfo.replicas().stream().map(Node::id).collect(Collectors.toList());
                List<Integer> isr = partitionInfo.isr().stream().map(Node::id).collect(Collectors.toList());

                boolean isUnderReplicated = isr.size() < replicas.size();
                boolean isPreferredLeader = partitionInfo.leader() != null &&
                        !replicas.isEmpty() &&
                        partitionInfo.leader().id() == replicas.get(0);

                long earliestOffset = 0L, latestOffset = 0L, messageCount = 0L;
                try {
                    TopicPartition tp = new TopicPartition(topicName, partitionInfo.partition());
                    earliestOffset = adminClient
                            .listOffsets(Map.of(tp, OffsetSpec.earliest()))
                            .all()
                            .get()
                            .get(tp)
                            .offset();
                    latestOffset = adminClient
                            .listOffsets(Map.of(tp, OffsetSpec.latest()))
                            .all()
                            .get()
                            .get(tp)
                            .offset();
                    messageCount = latestOffset - earliestOffset;
                } catch (Exception ex) {
                    logger.warn("Could not fetch offsets for topic {} partition {}", topicName, partitionInfo.partition(), ex);
                }

                return new PartitionDetails(
                        partitionInfo.partition(),
                        partitionInfo.leader() != null ? partitionInfo.leader().id() : -1,
                        replicas,
                        isr,
                        isUnderReplicated,
                        isPreferredLeader,
                        earliestOffset,
                        latestOffset,
                        messageCount
                );
            }).collect(Collectors.toList());

            logger.info("Partition details built for {} partitions", partitions.size());

            // Fetch consumer groups for the topic
            List<ConsumerGroupInfo> consumerGroups = new ArrayList<>();
            Collection<ConsumerGroupListing> groupListings = adminClient.listConsumerGroups().all().get();
            for (ConsumerGroupListing groupListing : groupListings) {
                String groupId = groupListing.groupId();
                ConsumerGroupDescription groupDescription = adminClient
                        .describeConsumerGroups(List.of(groupId))
                        .describedGroups()
                        .get(groupId)
                        .get();

                boolean hasTopic = groupDescription.members().stream()
                        .flatMap(member -> member.assignment().topicPartitions().stream())
                        .anyMatch(tp -> tp.topic().equals(topicName));
                if (!hasTopic) continue;

                List<ConsumerMemberInfo> members = groupDescription.members().stream()
                        .map(memberDesc -> new ConsumerMemberInfo(
                                memberDesc.consumerId(),
                                memberDesc.groupInstanceId().orElse(""),
                                memberDesc.clientId(),
                                memberDesc.host(),
                                memberDesc.assignment().topicPartitions().stream()
                                        .filter(tp -> tp.topic().equals(topicName))
                                        .map(TopicPartition::partition)
                                        .collect(Collectors.toList())
                        ))
                        .collect(Collectors.toList());

                consumerGroups.add(new ConsumerGroupInfo(
                        groupDescription.groupId(),
                        groupDescription.groupState().toString(),
                        groupDescription.groupEpoch().orElse(0),
                        members
                ));
            }
            logger.info("Consumer groups retrieved: {} groups", consumerGroups.size());

            TopicDetails result = new TopicDetails(
                    topicName,
                    partitionCount,
                    replicationFactor,
                    configurations,
                    partitions,
                    consumerGroups
            );

            logger.info("Topic details successfully created for: {}", topicName);
            return result;

        } catch (InterruptedException | ExecutionException e) {
            logger.error("Error getting topic details for: {}", topicName, e);
            return null;
        }
    }




    public List<PartitionMessage> getMessageAtOffset(String topicName, int partition, long offset) {
        TopicPartition topicPartition = new TopicPartition(topicName, partition);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProperties)) {
            var messages = getRecordAtOffset(consumer, topicPartition, offset);
            logger.info("Fetched {} messages from topic: {}, partition: {}, offset: {}", messages.size(), topicName, partition, offset);

            if(!messages.isEmpty()) {
                List<PartitionMessage> partitionMessages = new ArrayList<>();
                for (ConsumerRecord<String, String> record : messages) {
                    PartitionMessage partitionMessage = new PartitionMessage();
                    partitionMessage.setOffset(record.offset());
                    partitionMessage.setKey(record.key());
                    partitionMessage.setValue(record.value());
                    partitionMessage.setTimestamp(new Date(record.timestamp()));
                    partitionMessage.setHeaders(headersToMap(record.headers()));
                    partitionMessage.setPartition(record.partition());
                    partitionMessage.setTopic(record.topic());
                    partitionMessages.add(partitionMessage);
                }
                return partitionMessages;
            } else {
                return Collections.emptyList();
            }
        }
    }


    private Map<String, String> headersToMap(Headers headers) {
        if(headers == null) {
            return Collections.emptyMap();
        }
        Map<String, String> headersMap = new HashMap<>();
        for (var header : headers) {
            final var value = header.value();
            headersMap.put(header.key(), (value == null) ? null : new String(value));
        }
        logger.info("Converted headers to map: {}", headersMap);
        return headersMap;
    }

    private List<ConsumerRecord<String, String>> getRecordAtOffset(KafkaConsumer<String, String> consumer, TopicPartition topicPartition, long offset) {
        var partitions = Collections.singletonList(topicPartition);
        consumer.assign(partitions);
        consumer.seek(topicPartition, offset);

        var rawRecords = new ArrayList<ConsumerRecord<String, String>>();
        rawRecords.addAll(consumer.poll(Duration.ofMillis(100)).records(topicPartition));
        return rawRecords;
    }

    public List<PartitionMessage> getMessagesFromATopicPartition(String topicName, int partition) {
        TopicPartition topicPartition = new TopicPartition(topicName, partition);
        
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProperties)) {
            List<PartitionMessage> allMessages = new ArrayList<>();
            List<ConsumerRecord<String, String>> records = getRecordsFromTopic(consumer, topicPartition);
            for (ConsumerRecord<String, String> record : records) {
                PartitionMessage partitionMessage = new PartitionMessage();
                partitionMessage.setOffset(record.offset());
                partitionMessage.setKey(record.key());
                partitionMessage.setValue(record.value());
                partitionMessage.setTimestamp(new Date(record.timestamp()));
                partitionMessage.setHeaders(headersToMap(record.headers()));
                partitionMessage.setPartition(record.partition());
                partitionMessage.setTopic(record.topic());
                allMessages.add(partitionMessage);
            }
            return allMessages;
        }
    }

    public List<PartitionMessage> getMessagesFromTopic(String topicName) {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProperties)) {
            List<PartitionMessage> allMessages = new ArrayList<>();
            Set<TopicPartition> topicPartitions = consumer.partitionsFor(topicName).stream()
                    .map(partitionInfo -> new TopicPartition(partitionInfo.topic(), partitionInfo.partition()))
                    .collect(Collectors.toSet());

            for (TopicPartition topicPartition : topicPartitions) {
                List<ConsumerRecord<String, String>> records = getRecordsFromTopic(consumer, topicPartition);
                for (ConsumerRecord<String, String> record : records) {
                    PartitionMessage partitionMessage = new PartitionMessage();
                    partitionMessage.setOffset(record.offset());
                    partitionMessage.setKey(record.key());
                    partitionMessage.setValue(record.value());
                    partitionMessage.setTimestamp(new Date(record.timestamp()));
                    partitionMessage.setHeaders(headersToMap(record.headers()));
                    partitionMessage.setPartition(record.partition());
                    partitionMessage.setTopic(record.topic());
                    allMessages.add(partitionMessage);
                }
            }
            return allMessages;
        }
    }

    public List<ConsumerRecord<String, String>> getRecordsFromTopic(KafkaConsumer<String, String> consumer, TopicPartition topicPartition) {
        consumer.assign(Collections.singletonList(topicPartition));
        consumer.seekToBeginning(Collections.singletonList(topicPartition));
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        int maxPolls = 100;
        int pollCount = 0;
        
        while (pollCount < maxPolls) {
            var polledRecords = consumer.poll(Duration.ofMillis(100)).records(topicPartition);
            if (polledRecords.isEmpty()) {
                break;
            }
            records.addAll(polledRecords);
            pollCount++;
        }
        return records;
    }

    public void sendMessage(String topicName, String key, String value) {
        try {
            RecordMetadata sendResults = kafkaProducer.send(new org.apache.kafka.clients.producer.ProducerRecord<>(topicName, key, value))
                    .get(5, java.util.concurrent.TimeUnit.SECONDS);
            logger.info("Message sent to topic: {}, key: {}, value: {}, offset : {}, partition: {}", topicName, key, value, sendResults.offset(), sendResults.partition());
        } catch (Exception e) {
            logger.error("Error sending message to topic: {}, key: {}, value: {}", topicName, key, value, e);
            throw new RuntimeException("Failed to send message", e);
        }
    }
}
