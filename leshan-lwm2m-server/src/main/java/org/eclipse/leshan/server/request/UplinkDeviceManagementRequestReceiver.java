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
package org.eclipse.leshan.server.request;

import org.eclipse.leshan.core.endpoint.EndpointUri;
import org.eclipse.leshan.core.peer.LwM2mPeer;
import org.eclipse.leshan.core.request.UplinkDeviceManagementRequest;
import org.eclipse.leshan.core.response.LwM2mResponse;
import org.eclipse.leshan.core.response.SendableResponse;
import org.eclipse.leshan.server.profile.ClientProfile;

public interface UplinkDeviceManagementRequestReceiver {

    <T extends LwM2mResponse> SendableResponse<T> requestReceived(LwM2mPeer sender, ClientProfile senderProfile,
            UplinkDeviceManagementRequest<T> request, EndpointUri serverEndpointUri);

    void onError(LwM2mPeer sender, ClientProfile senderProfile, Exception exception,
            Class<? extends UplinkDeviceManagementRequest<? extends LwM2mResponse>> requestType,
            EndpointUri serverEndpointUri);
}
