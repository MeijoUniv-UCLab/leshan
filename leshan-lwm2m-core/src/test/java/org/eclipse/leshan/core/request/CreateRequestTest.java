/*******************************************************************************
 * Copyright (c) 2013-2015 Sierra Wireless and others.
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
 *     Natalia Krzykała Orange Polska S.A. - initial implementation
 *******************************************************************************/
package org.eclipse.leshan.core.request;

import org.eclipse.leshan.core.node.LwM2mResource;
import org.eclipse.leshan.core.request.exception.InvalidRequestException;
import org.junit.jupiter.api.Test;

import nl.jqno.equalsverifier.EqualsVerifier;

class CreateRequestTest {

    public class ExtendedCreateRequest extends CreateRequest {
        public ExtendedCreateRequest(ContentFormat contentFormat, int objectId, LwM2mResource... resources)
                throws InvalidRequestException {
            super(contentFormat, objectId, resources);
        }

        @Override
        public boolean canEqual(Object obj) {
            return (obj instanceof ExtendedCreateRequest);
        }
    }

    @Test
    public void assertEqualsHashcode() {
        EqualsVerifier.forClass(CreateRequest.class).withRedefinedSubclass(ExtendedCreateRequest.class)
                .withRedefinedSuperclass().withIgnoredFields("coapRequest").verify();
    }
}
