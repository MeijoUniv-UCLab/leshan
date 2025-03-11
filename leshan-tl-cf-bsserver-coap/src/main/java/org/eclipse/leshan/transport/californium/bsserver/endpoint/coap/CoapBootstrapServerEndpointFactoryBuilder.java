/*******************************************************************************
 * Copyright (c) 2023 Sierra Wireless and others.
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
package org.eclipse.leshan.transport.californium.bsserver.endpoint.coap;

import java.util.List;

import org.eclipse.californium.elements.config.Configuration;
import org.eclipse.californium.elements.config.Configuration.ModuleDefinitionsProvider;
import org.eclipse.leshan.core.endpoint.DefaultEndPointUriHandler;
import org.eclipse.leshan.core.endpoint.EndPointUriHandler;
import org.eclipse.leshan.core.endpoint.Protocol;
import org.eclipse.leshan.transport.californium.bsserver.endpoint.AbstractEndpointFactoryBuilder;

public class CoapBootstrapServerEndpointFactoryBuilder extends
        AbstractEndpointFactoryBuilder<CoapBootstrapServerEndpointFactoryBuilder, CoapBootstrapServerEndpointFactory> {

    public CoapBootstrapServerEndpointFactoryBuilder() {
        this(new DefaultEndPointUriHandler());
    }

    public CoapBootstrapServerEndpointFactoryBuilder(EndPointUriHandler uriHandler) {
        super(uriHandler);
    }

    @Override
    protected Protocol getSupportedProtocol() {
        return CoapBootstrapServerEndpointFactory.getSupportedProtocol();
    }

    @Override
    public void applyDefaultValue(Configuration configuration) {
        CoapBootstrapServerEndpointFactory.applyDefaultValue(configuration);

    }

    @Override
    public List<ModuleDefinitionsProvider> getModuleDefinitionsProviders() {
        return CoapBootstrapServerEndpointFactory.getModuleDefinitionsProviders();
    }

    @Override
    public CoapBootstrapServerEndpointFactory build() {
        return new CoapBootstrapServerEndpointFactory(uri, loggingTagPrefix, configuration,
                coapEndpointConfigInitializer, getUriHandler());
    }
}
