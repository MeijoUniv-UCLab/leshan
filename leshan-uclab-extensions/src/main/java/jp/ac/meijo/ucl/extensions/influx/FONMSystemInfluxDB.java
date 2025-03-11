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

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.eclipse.leshan.core.link.Link;
import org.eclipse.leshan.core.model.ResourceModel;
import org.eclipse.leshan.core.node.LwM2mChildNode;
import org.eclipse.leshan.core.node.LwM2mNode;
import org.eclipse.leshan.core.node.LwM2mObject;
import org.eclipse.leshan.core.node.LwM2mObjectInstance;
import org.eclipse.leshan.core.node.LwM2mResource;
import org.eclipse.leshan.core.response.LwM2mResponse;
import org.eclipse.leshan.server.demo.servlet.json.JacksonLinkSerializer;
import org.eclipse.leshan.server.demo.servlet.json.JacksonLwM2mNodeDeserializer;
import org.eclipse.leshan.server.demo.servlet.json.JacksonLwM2mNodeSerializer;
import org.eclipse.leshan.server.demo.servlet.json.JacksonResponseSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import com.influxdb.exceptions.InfluxException;

import jp.ac.meijo.ucl.extensions.server.UclabUnixDomainSocketServer;

/**
 * InfluxDB 操作と LwM2M リソース処理を管理するための FONMSystemInfluxDB クラス。
 */
public class FONMSystemInfluxDB {

    private static final Logger LOG = LoggerFactory.getLogger(FONMSystemInfluxDB.class);

    private static final int OBJECT_ID = 32769;
    private static final String CONFIG_FILE = "config.toml";
    private static final String XML_FILE = "32769.xml";

    private ArrayList<String> fieldName;
    private InfluxDBClient client;
    private WriteApiBlocking writeApi;
    private Document doc;
    private InfluxUnixDomainServer unix;
    private ObjectMapper mapper;

    /**
     * FONMSystemInfluxDBクラスのメインメソッド。 データベースを初期化し、Unixドメインソケットサーバーをスタートさせてリッスンします。
     */
    public static void main(String[] args) {
        FONMSystemInfluxDB db = new FONMSystemInfluxDB();
        db.unix = db.new InfluxUnixDomainServer(db);
        try {
            db.unix.start();
            LOG.info("InfluxUnixDomainServer started successfully");
        } catch (IOException e) {
            LOG.error("Failed to start InfluxUnixDomainServer", e);
        }
        db.unix.listen();
    }

    /**
     * FONMSystemInfluxDBのコンストラクタ。 XMLの解析、ObjectMapperの初期化を行います。 また、シャットダウンフックを追加してリソースの適切な解放を保証します。
     */
    public FONMSystemInfluxDB() {
        initializeXmlParsing();
        initializeObjectMapper();

        Runtime.getRuntime().addShutdownHook(new Thread(this::close));
    }

    /**
     * XML 解析を初期化して、リソース ID と測定データの関係を抽出します。
     */
    private void initializeXmlParsing() {
        try {
            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();

            doc = dBuilder.parse(XML_FILE);
            doc.getDocumentElement().normalize();

            NodeList nList = doc.getElementsByTagName("Item");
            fieldName = new ArrayList<>();
            for (int i = 0; i < nList.getLength(); i++) {
                Node n = nList.item(i);
                if (n.getNodeType() == Node.ELEMENT_NODE) {
                    Element element = (Element) n;
                    this.fieldName.add(element.getElementsByTagName("Name").item(0).getTextContent());
                }
            }
            LOG.info("XML parsing completed successfully");
        } catch (Exception e) {
            LOG.error("Failed to initialize field names", e);
        }
    }

    /**
     * オブジェクトマッパを初期化
     */
    private void initializeObjectMapper() {
        this.mapper = new ObjectMapper();
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        SimpleModule module = new SimpleModule();
        module.addSerializer(Link.class, new JacksonLinkSerializer());
        module.addSerializer(LwM2mResponse.class, new JacksonResponseSerializer());
        module.addSerializer(LwM2mNode.class, new JacksonLwM2mNodeSerializer());
        module.addDeserializer(LwM2mNode.class, new JacksonLwM2mNodeDeserializer());
        mapper.registerModule(module);
        LOG.info("ObjectMapper initialized with custom serializers and deserializers");
    }

    /**
     * InfluxDBに接続します。 設定ファイルから接続情報を読み取り、クライアントを初期化します。
     */
    public void connect() {
        final TomlMapper mapper = new TomlMapper();
        try {
            FONMSystemInfluxDBConfig config = mapper.readValue(new File(CONFIG_FILE), FONMSystemInfluxDBConfig.class);
            client = InfluxDBClientFactory.create(config.url + ":" + config.port, config.token.toCharArray(),
                    config.org, config.bucket);
            writeApi = client.getWriteApiBlocking();
            LOG.info("Successfully connected to InfluxDB");
        } catch (IOException e) {
            LOG.error("Failed to read configuration file", e);
        }
    }

    /**
     * InfluxDBクライアントを閉じます。
     */
    public void close() {
        if (client != null) {
            client.close();
            LOG.info("InfluxDB client connection closed");
        }
    }

    /**
     * LwM2mObjectのリソースをInfluxDBに書き込みます。
     *
     * @param obj
     *            書き込むLwM2mObject
     */
    public void writeLwM2mResources(LwM2mObject obj) {
        if (Objects.isNull(obj)) {
            LOG.warn("Received null LwM2mObject, skipping write operation");
            return;
        }

        if (obj.getId() == OBJECT_ID) {
            obj.getInstances().forEach((k, v) -> writeLwM2mResources(obj.getId(), v));
            LOG.info("LwM2m resources written to InfluxDB for object ID: {}", OBJECT_ID);
        } else {
            LOG.warn("Received LwM2mObject with unexpected ID: {}", obj.getId());
        }
    }

    /**
     * 特定のオブジェクトインスタンスのLwM2mリソースをInfluxDBに書き込みます。
     *
     * @param objId
     *            オブジェクトID
     * @param instance
     *            書き込むLwM2mObjectInstance
     */
    private void writeLwM2mResources(int objId, LwM2mObjectInstance instance) {
        if (Objects.isNull(instance)) {
            LOG.warn("Received null LwM2mObjectInstance for object ID: {}", objId);
            return;
        }
        writeLwM2mResources(objId, instance.getId(), instance.getResources());
    }

    /**
     * 特定のオブジェクトインスタンスとそのリソースをInfluxDBに書き込みます。
     *
     * @param objId
     *            オブジェクトID
     * @param objInstId
     *            オブジェクトインスタンスID
     * @param resources
     *            書き込むリソースのマップ
     */
    private void writeLwM2mResources(int objId, int objInstId, Map<Integer, LwM2mResource> resources) {
        if (Objects.isNull(resources)) {
            LOG.warn("WriteApi or resources are null for object ID: {}, instance ID: {}", objId, objInstId);
            return;
        }

        if (Objects.isNull(writeApi)) {
            connect();
        }

        ArrayList<Point> points = createPoints(objInstId, resources);

        try {
            writeApi.writePoints(points);
            LOG.info("Successfully wrote {} points to InfluxDB for object ID: {}, instance ID: {}", points.size(),
                    objId, objInstId);
        } catch (InfluxException e) {
            LOG.error("Failed to write points to InfluxDB", e);
        }
    }

    /**
     * LwM2mリソースからInfluxDBのポイントを作成します。
     *
     * @param objInstId
     *            オブジェクトインスタンスID
     * @param resources
     *            リソースのマップ
     *
     * @return InfluxDBポイントのリスト
     */
    private ArrayList<Point> createPoints(int objInstId, Map<Integer, LwM2mResource> resources) {
        String deviceId = resources.entrySet().stream()
                .filter(entry -> fieldName.get(entry.getKey().intValue()).equals("device id")).findFirst()
                .map(entry -> entry.getValue().getValue().toString()).orElse(null);

        if (deviceId == null) {
            LOG.warn("Device ID not found for object instance: {}", objInstId);
        }

        ArrayList<Point> points = new ArrayList<>();

        for (Map.Entry<Integer, LwM2mResource> entry : resources.entrySet()) {
            Point point = Point.measurement("instance_" + objInstId)
                    .time(Instant.now().toEpochMilli(), WritePrecision.MS).addTag("device_id", deviceId);

            LwM2mResource resource = entry.getValue();
            String resourceId = "resource_" + resource.getId();

            if (resource.isMultiInstances()) {
                processMultiInstanceResource(resource, resourceId, point, deviceId);
            } else {
                processSingleInstanceResource(resource, resourceId, point);
            }
            points.add(point);
        }

        return points;
    }

    /**
     * マルチインスタンスのLwM2mリソースを処理し、InfluxDBポイントに追加します。
     *
     * @param resource
     *            処理するLwM2mResource
     * @param resourceId
     *            リソースID文字列
     * @param point
     *            追加先のInfluxDBポイント
     * @param deviceId
     *            デバイスID
     */
    private void processMultiInstanceResource(LwM2mResource resource, String resourceId, Point point, String deviceId) {
        resource.getInstances().forEach((k, v) -> {
            String fieldName = resourceId + "_" + k.toString();
            addFieldBasedOnType(point, fieldName, v.getType(), v.getValue());
        });
        LOG.debug("Processed multi-instance resource: {} for device: {}", resourceId, deviceId);
    }

    /**
     * シングルインスタンスのLwM2mリソースを処理し、InfluxDBポイントに追加します。
     *
     * @param resource
     *            処理するLwM2mResource
     * @param resourceId
     *            リソースID文字列
     * @param point
     *            追加先のInfluxDBポイント
     */
    private void processSingleInstanceResource(LwM2mResource resource, String resourceId, Point point) {
        addFieldBasedOnType(point, resourceId, resource.getType(), resource.getValue());
        point.addTag("resource_name", this.fieldName.get(resource.getId()).replaceAll("[ \\-_]", "_"));
        LOG.debug("Processed single-instance resource: {}", resourceId);
    }

    /**
     * リソースの型に基づいてフィールドをInfluxDBポイントに追加します。
     *
     * @param point
     *            フィールドを追加するInfluxDBポイント
     * @param fieldName
     *            フィールド名
     * @param type
     *            リソースの型
     * @param value
     *            リソースの値
     */
    private void addFieldBasedOnType(Point point, String fieldName, ResourceModel.Type type, Object value) {
        switch (type) {
        case BOOLEAN:
            point.addField(fieldName, (Boolean) value);
            break;
        case STRING:
            point.addField(fieldName, value.toString());
            break;
        case OPAQUE:
            LOG.warn("Unexpected value type for field: {}", fieldName);
            break;
        case TIME:
            // These types are not processed
            LOG.debug("Skipping unsupported type: {} for field: {}", type, fieldName);
            break;
        default:
            if (value instanceof Number) {
                point.addField(fieldName, (Number) value);
            } else {
                LOG.warn("Unexpected value type for field: {}", fieldName);
            }
            break;
        }
    }

    /**
     * ソケット接続を扱うインナークラス。
     */
    private class InfluxUnixDomainServer extends UclabUnixDomainSocketServer {
        private final FONMSystemInfluxDB db;

        public InfluxUnixDomainServer(FONMSystemInfluxDB db) {
            this.db = db;
        }

        @Override
        protected boolean processClientRequest(SocketChannel clientChannel) throws IOException {
            ByteBuffer bb = read(clientChannel);
            String str = new String(bb.array(), 0, bb.limit(), StandardCharsets.UTF_8);
            LOG.info("Received message from client: {}", str);

            try {
                LwM2mChildNode node = (LwM2mChildNode) mapper.readValue(str, LwM2mNode.class);
                if (!(node instanceof LwM2mObject)) {
                    LOG.warn("Received node is not an LwM2mObject");
                    return false;
                }
                LwM2mObject obj = (LwM2mObject) node;
                db.writeLwM2mResources(obj);
                return true;
            } catch (Exception e) {
                LOG.error("Error processing client request", e);
                return false;
            }
        }
    }
}
