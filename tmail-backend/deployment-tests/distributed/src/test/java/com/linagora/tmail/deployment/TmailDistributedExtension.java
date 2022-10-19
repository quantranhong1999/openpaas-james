package com.linagora.tmail.deployment;

import static com.linagora.tmail.deployment.ThirdPartyContainers.OS_IMAGE_NAME;
import static com.linagora.tmail.deployment.ThirdPartyContainers.OS_NETWORK_ALIAS;
import static com.linagora.tmail.deployment.ThirdPartyContainers.createCassandra;
import static com.linagora.tmail.deployment.ThirdPartyContainers.createSearchContainer;
import static com.linagora.tmail.deployment.ThirdPartyContainers.createRabbitMQ;
import static com.linagora.tmail.deployment.ThirdPartyContainers.createS3;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.UUID;

import org.apache.james.mpt.imapmailbox.external.james.host.external.ExternalJamesConfiguration;
import org.apache.james.util.Port;
import org.apache.james.util.Runnables;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.utility.MountableFile;

public class TmailDistributedExtension implements BeforeEachCallback, AfterEachCallback {
    private final Network network;
    private final GenericContainer<?> cassandra;
    private final GenericContainer<?> opensearch;
    private final GenericContainer<?> rabbitmq;
    private final GenericContainer<?> s3;
    private final GenericContainer<?> james;

    public TmailDistributedExtension() {
        network = Network.newNetwork();
        cassandra = createCassandra(network);
        opensearch = createSearchContainer(network, OS_IMAGE_NAME, OS_NETWORK_ALIAS);
        rabbitmq = createRabbitMQ(network);
        s3 = createS3(network);
        james = createTmailDistributed();
    }

    @SuppressWarnings("resource")
    private GenericContainer<?> createTmailDistributed() {
        return new GenericContainer<>("linagora/team-mail-backend-distributed:latest")
            .withNetworkAliases("james-distributed")
            .withNetwork(network)
            .dependsOn(cassandra, opensearch, s3, rabbitmq)
            .withCopyFileToContainer(MountableFile.forClasspathResource("james-conf/imapserver.xml"), "/root/conf/")
            .withCopyFileToContainer(MountableFile.forClasspathResource("james-conf/jwt_privatekey"), "/root/conf/")
            .withCopyFileToContainer(MountableFile.forClasspathResource("james-conf/jwt_publickey"), "/root/conf/")
            .withCreateContainerCmdModifier(createContainerCmd -> createContainerCmd.withName("team-mail-distributed-testing" + UUID.randomUUID()))
            .waitingFor(TestContainerWaitStrategy.WAIT_STRATEGY)
            .withExposedPorts(25, 143, 80);
    }

    @Override
    public void beforeEach(ExtensionContext extensionContext) throws IOException {
        String dockerSaveFileUrl = new File("").getAbsolutePath().replace(Paths.get("tmail-backend", "deployment-tests", "distributed").toString(),
            Paths.get("tmail-backend", "apps", "distributed", "target", "jib-image.tar").toString());
        james.getDockerClient().loadImageCmd(Files.newInputStream(Paths.get(dockerSaveFileUrl))).exec();

        Runnables.runParallel(
            cassandra::start,
            opensearch::start,
            rabbitmq::start,
            s3::start);
        james.start();
    }

    @Override
    public void afterEach(ExtensionContext extensionContext) {
        james.stop();
        Runnables.runParallel(
            cassandra::stop,
            opensearch::stop,
            rabbitmq::stop,
            s3::stop);
    }

    public GenericContainer<?> getContainer() {
        return james;
    }

    ExternalJamesConfiguration configuration() {
        return new ExternalJamesConfiguration() {
            @Override
            public String getAddress() {
                return james.getContainerIpAddress();
            }

            @Override
            public Port getImapPort() {
                return Port.of(james.getMappedPort(143));
            }

            @Override
            public Port getSmptPort() {
                return Port.of(james.getMappedPort(25));
            }
        };
    }
}
