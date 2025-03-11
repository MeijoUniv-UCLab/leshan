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
package org.eclipse.leshan.transport.californium.bsserver.endpoint;

import org.eclipse.californium.core.network.CoapEndpoint;
import org.eclipse.californium.elements.config.Configuration;
import org.eclipse.leshan.bsserver.LeshanBootstrapServer;
import org.eclipse.leshan.core.endpoint.EndpointUri;
import org.eclipse.leshan.core.endpoint.Protocol;
import org.eclipse.leshan.servers.security.ServerSecurityInfo;
import org.eclipse.leshan.transport.californium.ExceptionTranslator;
import org.eclipse.leshan.transport.californium.identity.IdentityHandler;

public interface CaliforniumBootstrapServerEndpointFactory {

    Protocol getProtocol();

    EndpointUri getUri();

    String getEndpointDescription();

    CoapEndpoint createCoapEndpoint(Configuration defaultCaliforniumConfiguration,
            ServerSecurityInfo serverSecurityInfo, LeshanBootstrapServer server);

    IdentityHandler createIdentityHandler();

    ExceptionTranslator createExceptionTranslator();
}
