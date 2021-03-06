package com.cerner.common.kafka.admin;

import kafka.admin.AdminClient;
import kafka.admin.AdminOperationException;
import kafka.common.TopicAndPartition;
import kafka.security.auth.Acl;
import kafka.security.auth.Authorizer;
import kafka.security.auth.Resource;
import kafka.security.auth.SimpleAclAuthorizer;
import kafka.utils.VerifiableProperties;
import kafka.utils.ZKConfig;
import kafka.utils.ZkUtils;
import kafka.zookeeper.ZooKeeperClientException;
import org.I0Itec.zkclient.ZkClient;
import org.I0Itec.zkclient.ZkConnection;
import org.I0Itec.zkclient.exception.ZkException;
import org.apache.commons.lang3.StringUtils;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.kafka.clients.admin.NewPartitions;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.errors.InvalidPartitionsException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.apache.kafka.common.security.auth.KafkaPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Tuple2;
import scala.collection.Iterator;
import scala.collection.immutable.Set$;
import scala.collection.mutable.ListBuffer;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * A client for administering a Kafka cluster
 *
 * <p>
 * This class is not thread safe.
 * </p>
 *
 * @author Bryan Baugher
 * @deprecated Use Kafka's new AdminClient
 */
@Deprecated
public class KafkaAdminClient implements Closeable {

    /**
     * Reference to the logger for this resource
     */
    private static final Logger LOG = LoggerFactory.getLogger(KafkaAdminClient.class);

    /**
     * Property used to control if zookeeper should be connected securely
     */
    public static final String ZOOKEEPER_SECURE = "zookeeper.secure";

    /**
     * Default value for {@link #ZOOKEEPER_SECURE}
     */
    public static final String DEFAULT_ZOOKEEPER_SECURE = Boolean.FALSE.toString();

    /**
     * Property used to control the maximum amount of time in ms to wait for any operation to complete
     */
    public static final String OPERATION_TIMEOUT_MS = "kafka.admin.operation.timeout";

    /**
     * The default value for {@link #OPERATION_TIMEOUT_MS} (30_000 ms or 30s)
     */
    public static final String DEFAULT_OPERATION_TIMEOUT_MS = String.valueOf(30_000);

    /**
     * Property used to control the amount of time in ms to sleep before verifying if an asynchronous Kafka operation
     * was successful
     */
    public static final String OPERATION_SLEEP_MS = "kafka.admin.operation.sleep";

    /**
     * The default value for {@link #OPERATION_SLEEP_MS} (50ms)
     */
    public static final String DEFAULT_OPERATION_SLEEP_MS = String.valueOf(50);

    /**
     * Zookeeper client used to talk to Kafka
     */
    protected ZkUtils zkUtils = null;

    /**
     * The properties used to configure the client
     */
    private final Properties properties;

    /**
     * The time in ms to wait for any operation to complete
     */
    protected final long operationTimeout;

    /**
     * The amount of time to sleep before before verifying if an asynchronous Kafka operation was successful
     */
    protected final long operationSleep;

    /**
     * Authorization client to make ACL requests. Lazily created
     */
    private Authorizer authorizer = null;

    /**
     * Admin client from Kafka to look up information about consumer groups
     */
    private AdminClient adminClient = null;

    /**
     * New java admin client
     */
    private org.apache.kafka.clients.admin.AdminClient newAdminClient = null;

    /**
     * Creates a Kafka admin client with the given properties
     *
     * @param properties
     *      the properties to use to connect to the Kafka cluster
     * @throws IllegalArgumentException
     * <ul>
     *     <li>if properties is {@code null}</li>
     *     <li>if the property for {@link ZKConfig#ZkConnectProp()} is not set</li>
     *     <li>if the property for {@link #OPERATION_TIMEOUT_MS} is not a number or less than zero</li>
     *     <li>if the property for {@link #OPERATION_SLEEP_MS} is not a number or less than zero</li>
     * </ul>
     */
    public KafkaAdminClient(Properties properties) {
        if (properties == null)
            throw new IllegalArgumentException("properties cannot be null");

        if (properties.getProperty(ZKConfig.ZkConnectProp()) == null)
            throw new IllegalArgumentException("missing required property: " + ZKConfig.ZkConnectProp());

        this.properties = properties;
        this.operationTimeout = parseLong(properties, OPERATION_TIMEOUT_MS, DEFAULT_OPERATION_TIMEOUT_MS);
        this.operationSleep = parseLong(properties, OPERATION_SLEEP_MS, DEFAULT_OPERATION_SLEEP_MS);

        if (operationTimeout < 0)
            throw new IllegalArgumentException("operationTimeout cannot be < 0");

        if (operationSleep < 0)
            throw new IllegalArgumentException("operationSleep cannot be < 0");
    }

    // Visible for testing
    ZkUtils getZkUtils() {
        if (zkUtils == null) {
            Tuple2<ZkClient, ZkConnection> tuple;
            try {
                ZKConfig zkConfig = new ZKConfig(new VerifiableProperties(properties));
                tuple = ZkUtils.createZkClientAndConnection(zkConfig.zkConnect(), zkConfig.zkSessionTimeoutMs(),
                    zkConfig.zkConnectionTimeoutMs());
            } catch (ZkException | ZooKeeperClientException e) {
                throw new AdminOperationException("Unable to create admin connection", e);
            }

            boolean isSecure = Boolean.valueOf(properties.getProperty(ZOOKEEPER_SECURE, DEFAULT_ZOOKEEPER_SECURE));
            zkUtils = new ZkUtils(tuple._1(), tuple._2(), isSecure);
        }

        return zkUtils;
    }

    private static long parseLong(Properties properties, String property, String defaultValue) {
        String value = properties.getProperty(property, defaultValue);
        try {
            return Long.parseLong(value);
        } catch(NumberFormatException e) {
            throw new IllegalArgumentException("Unable to parse property [" + property + "] with value [" + value +
                    "]. Expected long", e);
        }
    }

    /**
     * Returns the set of all topics in the Kafka cluster
     *
     * @return unmodifiable set of all topics in the Kafka cluster
     *
     * @throws AdminOperationException
     *      if there is an issue retrieving the set of all topics
     */
    public Set<String> getTopics() {
        LOG.debug("Retrieving all topics");
        try {
            Set<String> topics = getNewAdminClient()
                .listTopics(new ListTopicsOptions().listInternal(true))
                .names().get(operationTimeout, TimeUnit.MILLISECONDS);
            if (topics.isEmpty()) {
                LOG.warn("Unable to list Kafka topics");
            }
            return Collections.unmodifiableSet(topics);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new AdminOperationException("Unable to list Kafka topics", e);
        }
    }

    /**
     * Returns the set of all partitions in the Kafka cluster
     *
     * @return unmodifiable set of all partitions in the Kafka cluster
     * @throws AdminOperationException
     *      if there is an issue reading partitions from Kafka
     */
    public Set<TopicAndPartition> getPartitions() {
        LOG.debug("Retrieving all partitions");
        return getPartitions(getTopics());
    }

    /**
     * Returns the set of all partitions for the given topic in the Kafka cluster
     *
     * @param topic
     *      a Kafka topic
     * @return unmodifiable set of all partitions for the given topic in the Kafka cluster
     * @throws AdminOperationException
     *      if there is an issue reading partitions from Kafka
     */
    public Set<TopicAndPartition> getPartitions(String topic) {
        LOG.debug("Retrieving all partitions for topic [{}]", topic);
        return getPartitions(Collections.singleton(topic));
    }

    private Set<TopicAndPartition> getPartitions(Collection<String> topics) {
        Set<TopicAndPartition> partitions = new HashSet<>();
        for (TopicDescription topicDescription : getTopicDescriptions(topics)) {
            for (TopicPartitionInfo partition : topicDescription.partitions()) {
                partitions.add(new TopicAndPartition(topicDescription.name(), partition.partition()));
            }
        }
        return Collections.unmodifiableSet(partitions);
    }

    private Collection<TopicDescription> getTopicDescriptions(Collection<String> topics) {
        try {
            Map<String, TopicDescription> topicDescriptions = getNewAdminClient().describeTopics(topics).all()
                .get(operationTimeout, TimeUnit.MILLISECONDS);
            if (topics.isEmpty()) {
                LOG.warn("Unable to describe Kafka topics");
            }
            return topicDescriptions.values();
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new AdminOperationException("Unable to describe Kafka topics", e);
        }
    }

    /**
     * Returns an {@link Authorizer} to make {@link Acl} requests
     *
     * @return an {@link Authorizer} to make {@link Acl} requests
     *
     * @throws AdminOperationException
     *      if there is an issue creating the authorizer
     */
    public Authorizer getAuthorizer() {
        if (authorizer == null) {
            ZKConfig zkConfig = new ZKConfig(new VerifiableProperties(properties));

            Map<String, Object> authorizerProps = new HashMap<>();
            authorizerProps.put(ZKConfig.ZkConnectProp(), zkConfig.zkConnect());
            authorizerProps.put(ZKConfig.ZkConnectionTimeoutMsProp(), zkConfig.zkConnectionTimeoutMs());
            authorizerProps.put(ZKConfig.ZkSessionTimeoutMsProp(), zkConfig.zkSessionTimeoutMs());
            authorizerProps.put(ZKConfig.ZkSyncTimeMsProp(), zkConfig.zkSyncTimeMs());

            try {
                Authorizer simpleAclAuthorizer = new SimpleAclAuthorizer();
                simpleAclAuthorizer.configure(authorizerProps);
                authorizer = simpleAclAuthorizer;
            } catch (ZkException | ZooKeeperClientException e) {
                throw new AdminOperationException("Unable to create authorizer", e);
            }
        }

        return authorizer;
    }

    /**
     * Returns all {@link Acl}s defined in the Kafka cluster
     *
     * @return unmodifiable map of all {@link Acl}s defined in the Kafka cluster
     *
     * @throws AdminOperationException
     *      if there is an issue reading the {@link Acl}s
     */
    public Map<Resource, Set<Acl>> getAcls() {
        LOG.debug("Fetching all ACLs");
        try {
            return convertKafkaAclMap(getAuthorizer().getAcls());
        } catch (ZkException | ZooKeeperClientException e) {
            throw new AdminOperationException("Unable to retrieve all ACLs", e);
        }
    }

    /**
     * Returns all {@link Acl}s associated to the given {@link KafkaPrincipal}
     *
     * @param principal
     *      the {@link KafkaPrincipal} to look up {@link Acl}s for
     * @return unmodifiable map of all {@link Acl}s associated to the given {@link KafkaPrincipal}
     * @throws IllegalArgumentException
     *      if principal is {@code null}
     * @throws AdminOperationException
     *      if there is an issue reading the {@link Acl}s
     */
    public Map<Resource, Set<Acl>> getAcls(KafkaPrincipal principal) {
        if (principal == null)
            throw new IllegalArgumentException("principal cannot be null");

        LOG.debug("Fetching all ACLs for principal [{}]", principal);

        try {
            return convertKafkaAclMap(getAuthorizer().getAcls(principal));
        } catch (ZkException | ZooKeeperClientException e) {
            throw new AdminOperationException("Unable to retrieve ACLs for principal: " + principal, e);
        }
    }

    private static Map<Resource, Set<Acl>> convertKafkaAclMap(scala.collection.immutable.Map<Resource,
            scala.collection.immutable.Set<Acl>> aclMap) {
        return Collections.unmodifiableMap(convertToJavaMap(aclMap.iterator()).entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey(), e -> convertToJavaSet(e.getValue().iterator()))));
    }

    /**
     * Returns all {@link Acl}s associated to the given {@link Resource}
     *
     * @param resource
     *      the {@link Resource} to look up {@link Acl}s for
     * @return unmodifiable set of all {@link Acl}s associated to the given {@link Resource}
     * @throws IllegalArgumentException
     *      if resource is {@code null}
     * @throws AdminOperationException
     *      if there is an issue reading the {@link Acl}s
     */
    public Set<Acl> getAcls(Resource resource) {
        if (resource == null)
            throw new IllegalArgumentException("resource cannot be null");

        LOG.debug("Fetching all ACLs for resource [{}]", resource);

        try {
            return Collections.unmodifiableSet(convertToJavaSet(getAuthorizer().getAcls(resource).iterator()));
        } catch (ZkException | ZooKeeperClientException e) {
            throw new AdminOperationException("Unable to retrieve ACLs for resource: " + resource, e);
        }
    }

    /**
     * Adds the given {@link Acl}s to the {@link Resource}
     *
     * @param acls
     *      the {@link Acl}s to add
     * @param resource
     *      the {@link Resource} to add the {@link Acl}s to
     * @throws IllegalArgumentException
     *      if acls or resource is {@code null}
     * @throws AdminOperationException
     *      if there is an issue adding the {@link Acl}s
     */
    public void addAcls(Set<Acl> acls, Resource resource) {
        if (acls == null)
            throw new IllegalArgumentException("acls cannot be null");
        if (resource == null)
            throw new IllegalArgumentException("resource cannot be null");

        LOG.debug("Adding ACLs [{}] for resource [{}]", acls, resource);

        try {
            getAuthorizer().addAcls(toImmutableScalaSet(acls), resource);
        } catch (ZkException | ZooKeeperClientException | IllegalStateException e) {
            throw new AdminOperationException("Unable to add ACLs for resource: " + resource, e);
        }
    }

    /**
     * Removes the given {@link Acl}s from the {@link Resource}
     *
     * @param acls
     *      the {@link Acl}s to remove
     * @param resource
     *      the {@link Resource} to remove the {@link Acl}s from
     * @throws IllegalArgumentException
     *      if acls or resource is {@code null}
     * @throws AdminOperationException
     *      if there is an issue removing the {@link Acl}s
     */
    public void removeAcls(Set<Acl> acls, Resource resource) {
        if (acls == null)
            throw new IllegalArgumentException("acls cannot be null");
        if (resource == null)
            throw new IllegalArgumentException("resource cannot be null");

        LOG.debug("Removing ACLs [{}] for resource [{}]", acls, resource);

        try {
            getAuthorizer().removeAcls(toImmutableScalaSet(acls), resource);
        } catch (ZkException | ZooKeeperClientException e) {
            throw new AdminOperationException("Unable to remove ACLs for resource: " + resource, e);
        }
    }

    /**
     * Creates a topic with no config and blocks until a leader is elected for each partition
     *
     * @param topic
     *      the name of the topic
     * @param partitions
     *      the number of partitions
     * @param replicationFactor
     *      the replication factor
     * @throws IllegalArgumentException
     *      <ul>
     *          <li>If topic is {@code null}</li>
     *          <li>If partitions is less than 1</li>
     *          <li>If replicationFactor is less than 1</li>
     *      </ul>
     * @throws org.apache.kafka.common.errors.TopicExistsException
     *      if the topic already exists
     * @throws org.apache.kafka.common.errors.InvalidReplicationFactorException
     *      if the replication factor is larger than number of available brokers
     * @throws org.apache.kafka.common.errors.InvalidTopicException
     *      if the topic name contains illegal characters
     * @throws AdminOperationException
     *      if the operation times out waiting for the topic to be created with leaders elected for all partitions, or there is
     *      any other issue creating the topic
     */
    public void createTopic(String topic, int partitions, int replicationFactor) {
        createTopic(topic, partitions, replicationFactor, new Properties());
    }

    /**
     * Creates a topic and blocks until a leader is elected for each partition
     *
     * @param topic
     *      the name of the topic
     * @param partitions
     *      the number of partitions
     * @param replicationFactor
     *      the replication factor
     * @param topicConfig
     *      the config for the topic
     * @throws IllegalArgumentException
     *      <ul>
     *          <li>If topic is {@code null}</li>
     *          <li>If partitions is less than 1</li>
     *          <li>If replicationFactor is less than 1</li>
     *          <li>if topicConfig is {@code null}</li>
     *      </ul>
     * @throws org.apache.kafka.common.errors.TopicExistsException
     *      if the topic already exists
     * @throws org.apache.kafka.common.errors.InvalidReplicationFactorException
     *      if the replication factor is larger than number of available brokers
     * @throws org.apache.kafka.common.errors.InvalidTopicException
     *      if the topic name contains illegal characters
     * @throws AdminOperationException
     *      if the operation times out waiting for the topic to be created with leaders elected for all partitions, or there is
     *      any other issue creating the topic
     */
    public void createTopic(String topic, int partitions, int replicationFactor, Properties topicConfig) {
        if (topic == null)
            throw new IllegalArgumentException("topic cannot be null");
        if (partitions < 1)
            throw new IllegalArgumentException("partitions cannot be < 1");
        if (replicationFactor < 1)
            throw new IllegalArgumentException("replicationFactor cannot be < 1");
        if (topicConfig == null)
            throw new IllegalArgumentException("topicConfig cannot be null");

        LOG.debug("Creating topic [{}] with partitions [{}] and replication factor [{}] and topic config [{}]",
                 topic, partitions, replicationFactor, topicConfig );


        Map<String, String> topicProps = new HashMap<>();
        topicConfig.stringPropertyNames().stream()
            .forEach(propName -> topicProps.put(propName, topicConfig.getProperty(propName)));


        try {
            NewTopic newTopic = new NewTopic(topic, partitions, (short) replicationFactor).configs(topicProps);

            getNewAdminClient()
                .createTopics(Collections.singleton(newTopic))
                .all().get(operationTimeout, TimeUnit.MILLISECONDS);
        } catch (ExecutionException ee) {
            Throwable throwable = ee.getCause();
            if (throwable instanceof KafkaException) {
                throw (KafkaException) throwable;
            }
            throw new AdminOperationException("Unable to create topic: " + topic, ee);
        } catch (InterruptedException | TimeoutException e) {
            throw new AdminOperationException("Unable to create topic: " + topic, e);
        }

        long start = System.currentTimeMillis();
        boolean operationCompleted = false;

        do {
            LOG.debug("Sleeping for {} ms for create topic operation to complete for topic [{}]", operationSleep, topic);
            try {
                Thread.sleep(operationSleep);
            } catch (InterruptedException e) {
                throw new AdminOperationException("Interrupted waiting for topic " + topic + " to be created", e);
            }
            operationCompleted = topicExists(topic) && topicHasPartitions(topic, partitions);
        } while (!operationCompleted && !operationTimedOut(start));

        if (!operationCompleted)
            throw new AdminOperationException("Timeout waiting for topic " + topic + " to be created");
    }

    /**
     * Delete the given topic if it exists
     *
     * @param topic
     *      the topic to delete
     * @throws IllegalArgumentException
     *      if topic is null, empty or blank
     * @throws AdminOperationException
     *      if the operation times out before deleting the topic, or there is any other issue deleting the topic (no exception is
     *      thrown if the topic does not exist)
     */
    public void deleteTopic(String topic) {
        if (StringUtils.isBlank(topic))
            throw new IllegalArgumentException("topic cannot be null, empty or blank");

        LOG.debug("Deleting topic [{}]", topic);

        try {
            getNewAdminClient().deleteTopics(Collections.singleton(topic)).all().get(operationTimeout, TimeUnit.MILLISECONDS);

            long start = System.currentTimeMillis();
            boolean operationCompleted = false;

            do {
                LOG.debug("Sleeping for {} ms for delete topic operation to complete for topic [{}]", operationSleep, topic);
                try {
                    Thread.sleep(operationSleep);
                } catch (InterruptedException e) {
                    throw new AdminOperationException("Interrupted waiting for topic " + topic + " to be deleted", e);
                }
                operationCompleted = !topicExists(topic);
            } while (!operationCompleted && !operationTimedOut(start));

            if (!operationCompleted)
                throw new AdminOperationException("Timeout waiting for topic " + topic + " to be deleted");

        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            Throwable throwable = e.getCause();
            if (throwable instanceof UnknownTopicOrPartitionException) {
                LOG.warn("Topic [{}] to be deleted was not found", topic, e);
            } else {
                throw new AdminOperationException("Unable to delete topic: " + topic, e);
            }
        }
    }

    /**
     * Returns the {@link Properties} associated to the topic
     *
     * @param topic
     *      a Kafka topic
     * @return the {@link Properties} associated to the topic
     * @throws IllegalArgumentException
     *      if topic is null, empty or blank
     * @throws AdminOperationException
     *      if there is an issue reading the topic config
     */
    public Properties getTopicConfig(String topic) {
        if (StringUtils.isBlank(topic))
            throw new IllegalArgumentException("topic cannot be null, empty or blank");

        LOG.debug("Fetching topic config for topic [{}]", topic);

        try {
            ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, topic);
            Map<ConfigResource, Config> configs = getNewAdminClient()
                .describeConfigs(Collections.singleton(resource))
                .all()
                .get(operationTimeout, TimeUnit.MILLISECONDS);
            Config config = configs.get(resource);
            if (config == null) {
                throw new AdminOperationException("Unable to get topic config: " + topic);
            }

            Properties properties = new Properties();
            config.entries().stream()
                // We are only interested in any overrides that are set
                .filter(configEntry -> configEntry.source() == ConfigEntry.ConfigSource.DYNAMIC_TOPIC_CONFIG)
                .forEach(configEntry -> properties.setProperty(configEntry.name(), configEntry.value()));
            return properties;
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new AdminOperationException("Unable to retrieve configuration for topic: " + topic, e);
        }
    }

    /**
     * Updates the given topic's config with the {@link Properties} provided. This is not additive but a full
     * replacement
     *
     * @param topic
     *      the topic to update config for
     * @param properties
     *      the properties to assign to the topic
     * @throws IllegalArgumentException
     *      if topic is null, empty or blank, or properties is {@code null}
     * @throws AdminOperationException
     *      if there is an issue updating the topic config
     */
    public void updateTopicConfig(String topic, Properties properties) {
        if (StringUtils.isBlank(topic))
            throw new IllegalArgumentException("topic cannot be null, empty or blank");
        if (properties == null)
            throw new IllegalArgumentException("properties cannot be null");

        LOG.debug("Updating topic config for topic [{}] with config [{}]", topic, properties);

        try {
            List<ConfigEntry> configEntries = new ArrayList<>();
            for (String property : properties.stringPropertyNames()) {
                configEntries.add(new ConfigEntry(property, properties.getProperty(property)));
            }

            getNewAdminClient()
                .alterConfigs(
                    Collections.singletonMap(
                        new ConfigResource(ConfigResource.Type.TOPIC, topic),
                        new Config(configEntries)))
                .all()
                .get(operationTimeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new AdminOperationException("Unable to update configuration for topic: " + topic, e);
        }
    }

    /**
     * Returns the replication factor for the given topic
     *
     * @param topic
     *      a Kafka topic
     * @return the replication factor for the given topic
     *
     * @throws IllegalArgumentException
     *      if topic is null, empty or blank
     * @throws AdminOperationException
     *      if there is an issue retrieving the replication factor
     */
    public int getTopicReplicationFactor(String topic) {
        if (StringUtils.isBlank(topic))
            throw new IllegalArgumentException("topic cannot be null, empty or blank");

        LOG.debug("Getting replication factor for topic [{}]", topic);

        Collection<TopicDescription> topicDescription = getTopicDescriptions(Collections.singleton(topic));
        if (topicDescription.isEmpty()) {
            throw new AdminOperationException("Unable to get description for topic: " + topic);
        }

        List<TopicPartitionInfo> topicPartitions = topicDescription.iterator().next().partitions();
        if (topicPartitions.isEmpty()) {
            throw new AdminOperationException("Unable to get partitions for topic: " + topic);
        }

        return topicPartitions.get(0).replicas().size();
    }

    /**
     * Returns the number of partitions for the given topic
     *
     * @param topic
     *      a Kafka topic
     * @return the number of partitions for the given topic
     * @throws IllegalArgumentException
     *      if topic is null, empty or blank
     * @throws AdminOperationException
     *      if there is an issue looking up the partitions for the topic
     */
    public int getTopicPartitions(String topic) {
        if (StringUtils.isBlank(topic))
            throw new IllegalArgumentException("topic cannot be null, empty or blank");

        LOG.debug("Fetching topic partition count for topic [{}]", topic);
        return getPartitions(Collections.singleton(topic)).size();
    }

    /**
     * Adds partitions to the given topic
     *
     * @param topic
     *      the topic to add partitions to
     * @param partitions
     *      the number of partitions the topic should have
     * @throws IllegalArgumentException
     *      if topic is null, empty or blank, or partitions is less than or equal to 1
     * @throws AdminOperationException
     *      if the number of partitions is less than or equal to the topic's current partition count, the operation times
     *      out while waiting for partitions to be added, or any other issue occurs adding the partitions
     */
    public void addTopicPartitions(String topic, int partitions) {
        if (StringUtils.isBlank(topic))
            throw new IllegalArgumentException("topic cannot be null, empty or blank");
        if (partitions <= 1)
            throw new IllegalArgumentException("partitions cannot be <= 1");

        LOG.debug("Adding topic partitions for topic [{}] with partitions [{}]", topic, partitions);

        try {
            getNewAdminClient().createPartitions(Collections.singletonMap(topic, NewPartitions.increaseTo(partitions)))
                .all().get(operationTimeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            Throwable throwable = e.getCause();
            // This will be thrown if the topic already has the specified number of partitions, ignore
            if (!(throwable instanceof InvalidPartitionsException)) {
                throw new AdminOperationException("Unable to add partitions to topic: " + topic, e);
            }
        }

        long start = System.currentTimeMillis();
        boolean operationCompleted;

        do {
            LOG.debug("Sleeping for {} ms for add topic partition operation to complete for topic [{}]", operationSleep,
                    topic);
            try {
                Thread.sleep(operationSleep);
            } catch (InterruptedException e) {
                throw new AdminOperationException("Interrupted waiting for partitions to be added to topic: " + topic, e);
            }
            operationCompleted = topicHasPartitions(topic, partitions);
        } while (!operationCompleted && !operationTimedOut(start));

        if (!operationCompleted)
            throw new AdminOperationException("Timeout waiting for partitions to be added to topic: " + topic);
    }

    /**
     * Retrieves the {@link AdminClient.ConsumerGroupSummary} information from Kafka. If the specified group is not found then the
     * returned summary will have a {@link AdminClient.ConsumerGroupSummary#state()} of
     * {@link org.apache.kafka.common.ConsumerGroupState#DEAD}{@code .toString()}, no exception will be thrown in that case.
     *
     * @param consumerGroup
     *      the name of the consumer group
     * @return the {@link AdminClient.ConsumerGroupSummary} information from Kafka
     * @throws AdminOperationException
     *      if there is an issue retrieving the consumer group summary
     */
    public AdminClient.ConsumerGroupSummary getConsumerGroupSummary(String consumerGroup) {
        if (StringUtils.isBlank(consumerGroup))
            throw new IllegalArgumentException("consumerGroup cannot be null, empty or blank");

        try {
            return getAdminClient().describeConsumerGroup(consumerGroup, operationTimeout);
        } catch (KafkaException e) {
            throw new AdminOperationException("Unable to retrieve summary for consumer group: " + consumerGroup, e);
        }
    }

    /**
     * Returns the collection of consumer summaries about the consumers in the group or empty collection if the group does not exist
     * or is not active
     *
     * @param consumerGroup
     *      the name of the consumer group
     * @return unmodifiable collection of consumer summaries about the consumers in the group or empty collection if the group does
     *      not exist or is not active
     * @throws IllegalArgumentException
     *      if the consumerGroup is null, empty or blank
     * @throws AdminOperationException
     *      if an issue occurs retrieving the summaries
     */
    public Collection<AdminClient.ConsumerSummary> getConsumerGroupSummaries(String consumerGroup) {
        if (StringUtils.isBlank(consumerGroup))
            throw new IllegalArgumentException("consumerGroup cannot be null, empty or blank");

        AdminClient.ConsumerGroupSummary summary;

        try {
            // this will throw IAE if the consumer group is dead/empty
            summary = getAdminClient().describeConsumerGroup(consumerGroup, operationTimeout);
        } catch (IllegalArgumentException e) {
            LOG.debug("Error while attempting to describe consumer group {}", consumerGroup, e);
            return Collections.emptyList();
        } catch (KafkaException e) {
            throw new AdminOperationException("Unable to retrieve summaries for consumer group: " + consumerGroup, e);
        }

        return Collections.unmodifiableCollection(convertToJavaSet(summary.consumers().get().iterator()));
    }

    /**
     * Returns the consumer group assignments of partitions to client IDs or empty map if the group does not exist or is not active
     *
     * @param consumerGroup
     *      the name of the consumer group
     * @return unmodifiable map of the consumer group assignments of partitions to client IDs or empty map if the group does not
     *      exist or is not active
     * @throws IllegalArgumentException
     *      if the consumerGroup is null, empty or blank
     * @throws AdminOperationException
     *      if an issue occurs retrieving the assignments
     */
    public Map<TopicPartition, String> getConsumerGroupAssignments(String consumerGroup) {
        if (StringUtils.isBlank(consumerGroup))
            throw new IllegalArgumentException("consumerGroup cannot be null, empty or blank");

        Map<TopicPartition, String> assignments = new HashMap<>();

        Collection<AdminClient.ConsumerSummary> summaries = getConsumerGroupSummaries(consumerGroup);

        for (final AdminClient.ConsumerSummary consumerSummary : summaries) {
            Set<TopicPartition> topicPartitions = convertToJavaSet(consumerSummary.assignment().iterator());

            for (final TopicPartition topicPartition : topicPartitions) {
                assignments.put(topicPartition, consumerSummary.clientId());
            }
        }

        return Collections.unmodifiableMap(assignments);
    }

    private AdminClient getAdminClient() {
        if (adminClient == null)
            adminClient = AdminClient.create(properties);

        return adminClient;
    }

    private org.apache.kafka.clients.admin.AdminClient getNewAdminClient() {
        if (newAdminClient == null)
            newAdminClient = org.apache.kafka.clients.admin.AdminClient.create(properties);

        return newAdminClient;
    }

    @Override
    public void close() {
        if (zkUtils != null)
            zkUtils.close();

        if (authorizer != null)
            authorizer.close();

        if (adminClient != null)
            adminClient.close();

        if (newAdminClient != null)
            newAdminClient.close();
    }

    private boolean operationTimedOut(long start) {
        return System.currentTimeMillis() - start >= operationTimeout;
    }

    private boolean topicExists(String topic) {
        return getTopics().contains(topic);
    }

    private boolean topicHasPartitions(String topic, int partitions) {
        return getPartitions(topic).size() == partitions;
    }

    /**
     * Manually converting to scala set to avoid binary compatibility issues between scala versions when using JavaConverters
     */
    @SuppressWarnings("unchecked")
    static <T> scala.collection.immutable.Set<T> toImmutableScalaSet(Set<T> set) {
        ListBuffer<T> buffer = new ListBuffer<>();
        set.forEach((e) -> buffer.$plus$eq(e));
        return Set$.<T> MODULE$.apply(buffer);
    }

    /**
     * Manually converting to java set to avoid binary compatibility issues between scala versions when using JavaConverters
     */
    static <E> Set<E> convertToJavaSet(Iterator<E> iterator) {
        Set<E> set = new HashSet<>();
        while(iterator.hasNext()) {
            set.add(iterator.next());
        }
        return Collections.unmodifiableSet(set);
    }

    /**
     * Manually converting to java map to avoid binary compatibility issues between scala versions when using JavaConverters
     */
    static <K, V> Map<K, V> convertToJavaMap(Iterator<Tuple2<K, V>> mapIterator) {
        Map<K, V> map = new HashMap<>();
        while(mapIterator.hasNext()) {
            Tuple2<K, V> entry = mapIterator.next();
            map.put(entry.copy$default$1(), entry.copy$default$2());
        }
        return Collections.unmodifiableMap(map);
    }
}
