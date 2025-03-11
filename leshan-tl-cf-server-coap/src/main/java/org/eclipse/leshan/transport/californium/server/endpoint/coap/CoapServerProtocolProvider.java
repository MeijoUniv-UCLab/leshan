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
package org.eclipse.leshan.transport.californium.server.endpoint.coap;

import java.net.InetSocketAddress;
import java.util.List;

import org.eclipse.californium.core.config.CoapConfig;
import org.eclipse.californium.elements.config.Configuration;
import org.eclipse.californium.elements.config.Configuration.ModuleDefinitionsProvider;
import org.eclipse.leshan.core.endpoint.EndPointUriHandler;
import org.eclipse.leshan.core.endpoint.EndpointUri;
import org.eclipse.leshan.core.endpoint.Protocol;
import org.eclipse.leshan.transport.californium.server.endpoint.CaliforniumServerEndpointFactory;
import org.eclipse.leshan.transport.californium.server.endpoint.ServerProtocolProvider;

public class CoapServerProtocolProvider implements ServerProtocolProvider {

    @Override
    public Protocol getProtocol() {
        return CoapServerEndpointFactory.getSupportedProtocol();
    }

    @Override
    public void applyDefaultValue(Configuration configuration) {
        CoapServerEndpointFactory.applyDefaultValue(configuration);
    }

    @Override
    public List<ModuleDefinitionsProvider> getModuleDefinitionsProviders() {
        return CoapServerEndpointFactory.getModuleDefinitionsProviders();
    }

    @Override
    public CaliforniumServerEndpointFactory createDefaultEndpointFactory(EndpointUri uri,
            EndPointUriHandler uriHandler) {
        return new CoapServerEndpointFactoryBuilder(uriHandler).setURI(uri).build();
    }

    @Override
    public EndpointUri getDefaultUri(Configuration configuration, EndPointUriHandler uriHandler) {
        return uriHandler.createUri(getProtocol().getUriScheme(),
                new InetSocketAddress(configuration.get(CoapConfig.COAP_PORT)));
    }
}
