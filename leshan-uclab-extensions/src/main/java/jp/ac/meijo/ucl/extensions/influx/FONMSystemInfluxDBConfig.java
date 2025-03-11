/*******************************************************************************
 * Copyright (c) 2024 Kengo Shimizu and UCLab.
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
 *     Kengo Shimizu - implementation
 *******************************************************************************/

package jp.ac.meijo.ucl.extensions.influx;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class FONMSystemInfluxDBConfig {
    public String token;
    public String bucket;
    public String org;
    public String url;
    public int port;

    public FONMSystemInfluxDBConfig() {
    };
}
