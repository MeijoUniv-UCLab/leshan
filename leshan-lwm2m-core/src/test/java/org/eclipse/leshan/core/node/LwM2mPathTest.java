/*******************************************************************************
 * Copyright (c) 2021 Sierra Wireless and others.
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
package org.eclipse.leshan.core.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import nl.jqno.equalsverifier.EqualsVerifier;

public class LwM2mPathTest {

    public static List<String> ordererPaths = Arrays.asList( //
            "/", //
            "/1", //
            "/1/1", //
            "/1/1/1", //
            "/1/1/1/1", //
            "/2", //
            "/2/1", //
            "/2/1/1", //
            "/2/1/1/1" //
    );

    static Stream<org.junit.jupiter.params.provider.Arguments> equalsTestArguements() {
        return ordererPaths.stream().map(p -> arguments(p));
    }

    static Stream<Arguments> smallerTestArguments() {
        List<Arguments> argumentList = new ArrayList<>();
        for (int i = 0; i < ordererPaths.size() - 1; i++) {
            for (int j = i + 1; j < ordererPaths.size(); j++) {
                argumentList.add(arguments(ordererPaths.get(i), ordererPaths.get(j)));
            }
        }
        return argumentList.stream();
    }

    @ParameterizedTest(name = "[{0}] equals to [{0}]")
    @MethodSource("equalsTestArguements")
    public void test_equals(String path) {
        assertTrue(new LwM2mPath(path).compareTo(new LwM2mPath(path)) == 0);
        assertEquals(new LwM2mPath(path), new LwM2mPath(path));
    }

    @ParameterizedTest(name = "[{0}] smaller than [{1}]")
    @MethodSource("smallerTestArguments")
    public void assertFirstSmaller(String path1, String path2) {
        assertTrue(new LwM2mPath(path1).compareTo(new LwM2mPath(path2)) == -1);
        assertTrue(new LwM2mPath(path2).compareTo(new LwM2mPath(path1)) == 1);

    }

    @Test
    public void assertEqualsHashcode() {
        EqualsVerifier.forClass(LwM2mPath.class).withRedefinedSubclass(LwM2mIncompletePath.class).verify();
    }
}
