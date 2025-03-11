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
package org.eclipse.leshan.bsserver.request;

import org.eclipse.leshan.bsserver.BootstrapHandler;
import org.eclipse.leshan.core.endpoint.EndpointUri;
import org.eclipse.leshan.core.peer.LwM2mPeer;
import org.eclipse.leshan.core.request.BootstrapRequest;
import org.eclipse.leshan.core.request.UplinkBootstrapRequest;
import org.eclipse.leshan.core.request.UplinkBootstrapRequestVisitor;
import org.eclipse.leshan.core.request.UplinkRequest;
import org.eclipse.leshan.core.response.LwM2mResponse;
import org.eclipse.leshan.core.response.SendableResponse;

public class DefaultBootstrapUplinkRequestReceiver implements BootstrapUplinkRequestReceiver {

    private final BootstrapHandler bootstapHandler;

    public DefaultBootstrapUplinkRequestReceiver(BootstrapHandler bootstapHandler) {
        this.bootstapHandler = bootstapHandler;
    }

    @Override
    public void onError(LwM2mPeer sender, Exception exception,
            Class<? extends UplinkRequest<? extends LwM2mResponse>> requestType, EndpointUri serverEndpointUri) {
    }

    @Override
    public <T extends LwM2mResponse> SendableResponse<T> requestReceived(LwM2mPeer sender,
            UplinkBootstrapRequest<T> request, EndpointUri serverEndpointUri) {

        RequestHandler<T> requestHandler = new RequestHandler<T>(sender, serverEndpointUri);
        request.accept(requestHandler);
        return requestHandler.getResponse();
    }

    public class RequestHandler<T extends LwM2mResponse> implements UplinkBootstrapRequestVisitor {

        private final LwM2mPeer sender;
        private final EndpointUri serverEndpointUri;
        private SendableResponse<? extends LwM2mResponse> response;

        public RequestHandler(LwM2mPeer sender, EndpointUri serverEndpointUri) {
            this.sender = sender;
            this.serverEndpointUri = serverEndpointUri;
        }

        @Override
        public void visit(BootstrapRequest request) {
            response = bootstapHandler.bootstrap(sender, request, serverEndpointUri);
        }

        @SuppressWarnings("unchecked")
        public SendableResponse<T> getResponse() {
            return (SendableResponse<T>) response;
        }
    }

}
