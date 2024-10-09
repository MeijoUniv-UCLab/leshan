/*******************************************************************************
 * Copyright (c) 2022 Sierra Wireless and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * and Eclipse Distribution License v1.0 which accompany this distribution.
 *
 * The Eclipse Public License is available at
 *    http://www.eclipse.org/legal/epl-v20.html
 * and the Eclipse Distribution License is available at
 *    http://www.eclipse.org/org/documents/edl-v10.html.
 *
 * Contributors:
 *     Sierra Wireless - initial API and implementation
 *******************************************************************************/
package org.eclipse.leshan.transport.californium.server.endpoint;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.eclipse.californium.core.CoapServer;
import org.eclipse.californium.core.coap.Request;
import org.eclipse.californium.core.coap.Response;
import org.eclipse.californium.core.network.CoapEndpoint;
import org.eclipse.californium.core.observe.NotificationListener;
import org.eclipse.californium.core.server.resources.Resource;
import org.eclipse.californium.elements.config.Configuration;
import org.eclipse.californium.elements.config.Configuration.ModuleDefinitionsProvider;
import org.eclipse.leshan.core.endpoint.DefaultEndPointUriHandler;
import org.eclipse.leshan.core.endpoint.EndPointUriHandler;
import org.eclipse.leshan.core.endpoint.EndpointUri;
import org.eclipse.leshan.core.endpoint.Protocol;
import org.eclipse.leshan.core.observation.CompositeObservation;
import org.eclipse.leshan.core.observation.Observation;
import org.eclipse.leshan.core.observation.ObservationIdentifier;
import org.eclipse.leshan.core.observation.SingleObservation;
import org.eclipse.leshan.core.peer.LwM2mPeer;
import org.eclipse.leshan.core.response.AbstractLwM2mResponse;
import org.eclipse.leshan.core.response.ObserveCompositeResponse;
import org.eclipse.leshan.core.response.ObserveResponse;
import org.eclipse.leshan.core.util.NamedThreadFactory;
import org.eclipse.leshan.server.LeshanServer;
import org.eclipse.leshan.server.endpoint.LwM2mServerEndpoint;
import org.eclipse.leshan.server.endpoint.LwM2mServerEndpointsProvider;
import org.eclipse.leshan.server.endpoint.ServerEndpointToolbox;
import org.eclipse.leshan.server.observation.LwM2mNotificationReceiver;
import org.eclipse.leshan.server.profile.ClientProfile;
import org.eclipse.leshan.server.request.UplinkDeviceManagementRequestReceiver;
import org.eclipse.leshan.servers.security.ServerSecurityInfo;
import org.eclipse.leshan.transport.californium.ExceptionTranslator;
import org.eclipse.leshan.transport.californium.ObserveUtil;
import org.eclipse.leshan.transport.californium.identity.IdentityHandler;
import org.eclipse.leshan.transport.californium.identity.IdentityHandlerProvider;
import org.eclipse.leshan.transport.californium.server.RootResource;
import org.eclipse.leshan.transport.californium.server.endpoint.coap.CoapServerProtocolProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CaliforniumServerEndpointsProvider implements LwM2mServerEndpointsProvider {

    // TODO TL : provide a COAP/Californium API ? like previous LeshanServer.coapAPI()

    private final Logger LOG = LoggerFactory.getLogger(CaliforniumServerEndpointsProvider.class);

    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(1,
            new NamedThreadFactory("Leshan Async Request timeout"));

    private final Configuration serverConfig;
    private final List<CaliforniumServerEndpointFactory> endpointsFactory;
    private final ServerCoapMessageTranslator messagetranslator = new ServerCoapMessageTranslator();
    private final List<CaliforniumServerEndpoint> endpoints;
    private CoapServer coapServer;

    public CaliforniumServerEndpointsProvider() {
        this(new Builder().generateDefaultValue());
    }

    protected CaliforniumServerEndpointsProvider(Builder builder) {
        this.serverConfig = builder.serverConfiguration;
        this.endpointsFactory = builder.endpointsFactory;
        this.endpoints = new ArrayList<CaliforniumServerEndpoint>();
    }

    public CoapServer getCoapServer() {
        return coapServer;
    }

    @Override
    public List<LwM2mServerEndpoint> getEndpoints() {
        return Collections.unmodifiableList(endpoints);
    }

    @Override
    public LwM2mServerEndpoint getEndpoint(EndpointUri uri) {
        for (CaliforniumServerEndpoint endpoint : endpoints) {
            if (endpoint.getURI().equals(uri))
                return endpoint;
        }
        return null;
    }

    @Override
    public void createEndpoints(UplinkDeviceManagementRequestReceiver requestReceiver,
            LwM2mNotificationReceiver notificatonReceiver, ServerEndpointToolbox toolbox,
            ServerSecurityInfo serverSecurityInfo, LeshanServer server) {
        // create server;
        coapServer = new CoapServer(serverConfig) {
            @Override
            protected Resource createRoot() {
                return new RootResource();
            }
        };

        // create identity handler provider
        IdentityHandlerProvider identityHandlerProvider = new IdentityHandlerProvider();

        // create endpoints
        for (CaliforniumServerEndpointFactory endpointFactory : endpointsFactory) {
            // create Californium endpoint
            CoapEndpoint coapEndpoint = endpointFactory.createCoapEndpoint(serverConfig, serverSecurityInfo,
                    notificatonReceiver, server);

            if (coapEndpoint != null) {

                // create identity handler and add it to provider
                final IdentityHandler identityHandler = endpointFactory.createIdentityHandler();
                identityHandlerProvider.addIdentityHandler(coapEndpoint, identityHandler);

                // create exception translator;
                ExceptionTranslator exceptionTranslator = endpointFactory.createExceptionTranslator();

                // create LWM2M endpoint
                CaliforniumServerEndpoint lwm2mEndpoint = new CaliforniumServerEndpoint(endpointFactory.getProtocol(),
                        endpointFactory.getEndpointDescription(), coapEndpoint, messagetranslator, toolbox,
                        notificatonReceiver, identityHandler, exceptionTranslator, executor);
                endpoints.add(lwm2mEndpoint);

                // add Californium endpoint to coap server
                coapServer.addEndpoint(coapEndpoint);

                // add NotificationListener
                coapEndpoint.addNotificationListener(new NotificationListener() {

                    @Override
                    public void onNotification(Request coapRequest, Response coapResponse) {
                        // Get Observation
                        String regid = coapRequest.getUserContext().get(ObserveUtil.CTX_REGID);
                        Observation observation = server.getRegistrationStore().getObservation(regid,
                                new ObservationIdentifier(coapResponse.getToken().getBytes()));
                        if (observation == null) {
                            LOG.warn("Unexpected error: Unable to find observation with token {} for registration {}",
                                    coapResponse.getToken(), regid);
                            // We just log it because, this is probably caused by bad device behavior :
                            // https://github.com/eclipse-leshan/leshan/issues/1634
                            return;
                        }
                        // Get profile
                        LwM2mPeer client = identityHandler.getIdentity(coapResponse);
                        ClientProfile profile = toolbox.getProfileProvider().getProfile(client.getIdentity());
                        if (profile == null) {
                            LOG.warn("Unexpected error: Unable to find registration with id {} for observation {}",
                                    regid, coapResponse.getToken());
                            // We just log it because, this is probably caused by bad device behavior :
                            // https://github.com/eclipse-leshan/leshan/issues/1634
                            return;
                        }

                        // create Observe Response
                        try {
                            AbstractLwM2mResponse response = messagetranslator.createObserveResponse(observation,
                                    coapResponse, toolbox, profile);
                            if (observation instanceof SingleObservation) {
                                notificatonReceiver.onNotification((SingleObservation) observation, client, profile,
                                        (ObserveResponse) response);
                            } else if (observation instanceof CompositeObservation) {
                                notificatonReceiver.onNotification((CompositeObservation) observation, client, profile,
                                        (ObserveCompositeResponse) response);
                            }
                        } catch (Exception e) {
                            notificatonReceiver.onError(observation, client, profile, e);
                        }

                    }
                });
            }
        }

        // create resources
        List<Resource> resources = messagetranslator.createResources(requestReceiver, toolbox, identityHandlerProvider);
        coapServer.add(resources.toArray(new Resource[resources.size()]));
    }

    @Override
    public void start() {
        coapServer.start();

    }

    @Override
    public void stop() {
        coapServer.stop();

    }

    @Override
    public void destroy() {
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            LOG.warn("Destroying RequestSender was interrupted.", e);
        }
        coapServer.destroy();
    }

    public static class Builder {

        private final List<ServerProtocolProvider> protocolProviders;
        private Configuration serverConfiguration;
        private final List<CaliforniumServerEndpointFactory> endpointsFactory;
        private final EndPointUriHandler uriHandler;

        public Builder(ServerProtocolProvider... protocolProviders) {
            this(new DefaultEndPointUriHandler(), protocolProviders);
        }

        public Builder(EndPointUriHandler uriHandler, ServerProtocolProvider... protocolProviders) {
            // TODO TL : handle duplicate ?
            this.protocolProviders = new ArrayList<ServerProtocolProvider>();
            if (protocolProviders.length == 0) {
                this.protocolProviders.add(new CoapServerProtocolProvider());
            } else {
                this.protocolProviders.addAll(Arrays.asList(protocolProviders));
            }
            this.uriHandler = uriHandler;
            this.endpointsFactory = new ArrayList<>();
        }

        /**
         * Create Californium {@link Configuration} with all Module Definitions needed for protocols provided by this
         * endpoints provider.
         * <p>
         * Once, you create the configuration you should use {@link #setConfiguration(Configuration)}
         *
         * <pre>
         * // Create Builder
         * CaliforniumServerEndpointsProvider.Builder builder = new CaliforniumServerEndpointsProvider.Builder(
         *         new CoapServerProtocolProvider(), //
         *         new CoapsServerProtocolProvider());
         *
         * // Set custom Californium Configuration :
         * Configuration c = builder.createDefaultConfiguration();
         * c.set(DtlsConfig.DTLS_RECOMMENDED_CIPHER_SUITES_ONLY, true);
         * c.set(CoapConfig.ACK_TIMEOUT, 1, TimeUnit.SECONDS);
         * builder.setConfiguration(c);
         * </pre>
         */
        public Configuration createDefaultConfiguration() {
            // Get all Californium modules
            Set<ModuleDefinitionsProvider> moduleProviders = new HashSet<>();
            for (ServerProtocolProvider protocolProvider : protocolProviders) {
                moduleProviders.addAll(protocolProvider.getModuleDefinitionsProviders());
            }

            // create Californium Configuration
            Configuration configuration = new Configuration(
                    moduleProviders.toArray(new ModuleDefinitionsProvider[moduleProviders.size()]));

            // apply default value
            for (ServerProtocolProvider protocolProvider : protocolProviders) {
                protocolProvider.applyDefaultValue(configuration);
            }

            return configuration;
        }

        /**
         * Set {@link Configuration} used by the {@link CoapServer}. It will be shared by all endpoints created by this
         * endpoints provider.
         * <p>
         * {@link Configuration} provided SHOULD be created with {@link #createDefaultConfiguration()}.
         * <p>
         * It should generally not be used with {@link #setConfiguration(Consumer)}
         */
        public Builder setConfiguration(Configuration serverConfiguration) {
            this.serverConfiguration = serverConfiguration;
            return this;
        }

        /**
         * Create Californium {@link Configuration} with all needed Module Definitions for protocol provided by
         * {@link ServerProtocolProvider}s, then apply given consumer to it.
         *
         * <pre>
         * {@code
         * endpointsBuilder.setConfiguration(c -> {
         *     c.set(DtlsConfig.DTLS_RECOMMENDED_CIPHER_SUITES_ONLY, true);
         *     c.set(CoapConfig.ACK_TIMEOUT, 1, TimeUnit.SECONDS);
         * });
         * }
         * </pre>
         *
         * This is like doing :
         *
         * <pre>
         * Configuration c = endpointsBuilder.createDefaultConfiguration();
         * c.set(DtlsConfig.DTLS_RECOMMENDED_CIPHER_SUITES_ONLY, true);
         * c.set(CoapConfig.ACK_TIMEOUT, 1, TimeUnit.SECONDS);
         * endpointsBuilder.setConfiguration(c);
         * </pre>
         */
        public Builder setConfiguration(Consumer<Configuration> configurationSetter) {
            Configuration cfg = createDefaultConfiguration();
            configurationSetter.accept(cfg);

            // we set config once all is done without exception.
            this.serverConfiguration = cfg;
            return this;
        }

        public Builder addEndpoint(String uri) {
            return addEndpoint(uriHandler.createUri(uri));
        }

        public Builder addEndpoint(EndpointUri uri) {
            for (ServerProtocolProvider protocolProvider : protocolProviders) {
                // TODO TL : validate URI
                if (protocolProvider.getProtocol().getUriScheme().equals(uri.getScheme())) {
                    // TODO TL: handle duplicate addr
                    endpointsFactory.add(protocolProvider.createDefaultEndpointFactory(uri, uriHandler));
                }
            }
            // TODO TL: handle missing provider for given protocol
            return this;
        }

        public Builder addEndpoint(InetSocketAddress addr, Protocol protocol) {
            return addEndpoint(uriHandler.createUri(protocol.getUriScheme(), addr));
        }

        public Builder addEndpoint(CaliforniumServerEndpointFactory endpointFactory) {
            // TODO TL: handle duplicate addr
            endpointsFactory.add(endpointFactory);
            return this;
        }

        protected Builder generateDefaultValue() {
            if (serverConfiguration == null) {
                serverConfiguration = createDefaultConfiguration();
            }

            if (endpointsFactory.isEmpty()) {
                for (ServerProtocolProvider protocolProvider : protocolProviders) {
                    // TODO TL : handle duplicates
                    endpointsFactory.add(protocolProvider.createDefaultEndpointFactory(
                            protocolProvider.getDefaultUri(serverConfiguration, uriHandler), uriHandler));
                }
            }
            return this;
        }

        public CaliforniumServerEndpointsProvider build() {
            generateDefaultValue();
            return new CaliforniumServerEndpointsProvider(this);
        }
    }
}
