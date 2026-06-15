package com.proxyapp.config;

import com.proxyapp.codec.CodecRegistry;
import com.proxyapp.codec.JsonCodec;
import com.proxyapp.codec.RawCodec;
import com.proxyapp.codec.XmlCodec;
import com.proxyapp.connector.ConnectorFactory;
import com.proxyapp.connector.FtpConnector;
import com.proxyapp.connector.HttpConnector;
import com.proxyapp.connector.TcpConnector;
import com.proxyapp.control.ProxyControlPoller;
import com.proxyapp.control.ProxyControlStarter;
import com.proxyapp.control.Reconciler;
import com.proxyapp.ingress.FtpIngressListener;
import com.proxyapp.ingress.InboundGateway;
import com.proxyapp.ingress.TcpSocketServer;
import com.proxyapp.profile.Profile;
import com.proxyapp.profile.ProfileRegistry;
import com.proxyapp.routing.FilenamePatternResolver;
import com.proxyapp.routing.MessageCatalog;
import com.proxyapp.routing.MessageTypeResolver;
import com.proxyapp.routing.RoutingState;
import com.proxyapp.session.TcpSessionManager;
import io.temporal.client.ActivityClient;
import io.temporal.client.ActivityClientOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.WorkerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;

import java.util.List;

@Configuration
public class ProxyAppConfig {

    @Bean
    public Profile profile(ProxyProperties properties) {
        return ProfileRegistry.builtIn().require(properties.profile());
    }

    @Bean
    public MessageCatalog messageCatalog(Profile profile) {
        return profile.catalog();
    }

    @Bean
    public RoutingState routingState(MessageCatalog catalog) {
        return new RoutingState(catalog);
    }

    @Bean
    public CodecRegistry codecRegistry() {
        return new CodecRegistry(List.of(new JsonCodec(), new XmlCodec(), new RawCodec()));
    }

    @Bean
    public ConnectorFactory connectorFactory() {
        return new ConnectorFactory(List.of(new HttpConnector(), new TcpConnector(), new FtpConnector()));
    }

    @Bean
    public List<MessageTypeResolver> messageTypeResolvers() {
        return List.of(new FilenamePatternResolver());
    }

    /** Standalone-activity client; the starter exposes the service stubs + namespace. */
    @Bean
    public ActivityClient activityClient(WorkflowServiceStubs serviceStubs,
                                         @Value("${spring.temporal.namespace:default}") String namespace) {
        return ActivityClient.newInstance(serviceStubs,
                ActivityClientOptions.newBuilder().setNamespace(namespace).build());
    }

    @Bean
    public InboundGateway inboundGateway(RoutingState routingState, CodecRegistry codecRegistry,
                                         ActivityClient activityClient, ProxyProperties properties,
                                         List<MessageTypeResolver> resolvers) {
        return new InboundGateway(routingState, codecRegistry, activityClient, properties, resolvers);
    }

    @Bean
    public TcpSocketServer tcpSocketServer(InboundGateway gateway) {
        return new TcpSocketServer(gateway::handle);
    }

    @Bean
    public FtpIngressListener ftpIngressListener(InboundGateway gateway, ProxyProperties properties) {
        return new FtpIngressListener(gateway, properties);
    }

    /** Connection table for persistent device links; unsolicited frames flow to the ingress gateway. */
    @Bean
    public TcpSessionManager tcpSessionManager(InboundGateway gateway) {
        return new TcpSessionManager(gateway::enqueueSessionFrame);
    }

    @Bean
    public Reconciler reconciler(ProxyProperties properties, MessageCatalog catalog,
                                 RoutingState routingState, TcpSocketServer tcpSocketServer,
                                 FtpIngressListener ftpIngressListener,
                                 TcpSessionManager tcpSessionManager, WorkerFactory workerFactory) {
        return new Reconciler(properties, catalog, routingState, tcpSocketServer,
                ftpIngressListener, tcpSessionManager, workerFactory);
    }

    @Bean
    public ProxyControlStarter proxyControlStarter(WorkflowClient workflowClient,
                                                   ProxyProperties properties,
                                                   MessageCatalog catalog,
                                                   ResourceLoader resourceLoader) {
        return new ProxyControlStarter(workflowClient, properties, catalog, resourceLoader);
    }

    @Bean
    public ProxyControlPoller proxyControlPoller(WorkflowClient workflowClient,
                                                 ProxyControlStarter starter,
                                                 Reconciler reconciler,
                                                 RoutingState routingState,
                                                 TcpSocketServer tcpSocketServer,
                                                 FtpIngressListener ftpIngressListener,
                                                 TcpSessionManager tcpSessionManager,
                                                 ApplicationContext applicationContext) {
        return new ProxyControlPoller(workflowClient, starter, reconciler, routingState,
                tcpSocketServer, ftpIngressListener, tcpSessionManager, applicationContext);
    }
}
