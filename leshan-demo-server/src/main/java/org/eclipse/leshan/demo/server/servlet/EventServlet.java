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
 *     Sierra Wireless - initial API and implementation
 *     Michał Wadowski (Orange) - Add Observe-Composite feature.
 *     Orange - keep one JSON dependency
 *******************************************************************************/
package org.eclipse.leshan.demo.server.servlet;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.servlets.EventSource;
import org.eclipse.jetty.servlets.EventSourceServlet;
import org.eclipse.leshan.core.LwM2m.Version;
import org.eclipse.leshan.core.link.Link;
import org.eclipse.leshan.core.node.LwM2mChildNode;
import org.eclipse.leshan.core.node.LwM2mNode;
import org.eclipse.leshan.core.node.LwM2mObject;
import org.eclipse.leshan.core.node.LwM2mPath;
import org.eclipse.leshan.core.node.TimestampedLwM2mNodes;
import org.eclipse.leshan.core.observation.CompositeObservation;
import org.eclipse.leshan.core.observation.Observation;
import org.eclipse.leshan.core.observation.SingleObservation;
import org.eclipse.leshan.core.request.SendRequest;
import org.eclipse.leshan.core.response.ObserveCompositeResponse;
import org.eclipse.leshan.core.response.ObserveResponse;
import org.eclipse.leshan.demo.server.servlet.json.JacksonLinkSerializer;
import org.eclipse.leshan.demo.server.servlet.json.JacksonLwM2mNodeSerializer;
import org.eclipse.leshan.demo.server.servlet.json.JacksonRegistrationSerializer;
import org.eclipse.leshan.demo.server.servlet.json.JacksonRegistrationUpdateSerializer;
import org.eclipse.leshan.demo.server.servlet.json.JacksonVersionSerializer;
import org.eclipse.leshan.demo.server.servlet.log.CoapMessage;
import org.eclipse.leshan.demo.server.servlet.log.CoapMessageListener;
import org.eclipse.leshan.demo.server.servlet.log.CoapMessageTracer;
import org.eclipse.leshan.server.LeshanServer;
import org.eclipse.leshan.server.endpoint.LwM2mServerEndpoint;
import org.eclipse.leshan.server.observation.ObservationListener;
import org.eclipse.leshan.server.queue.PresenceListener;
import org.eclipse.leshan.server.registration.Registration;
import org.eclipse.leshan.server.registration.RegistrationListener;
import org.eclipse.leshan.server.registration.RegistrationUpdate;
import org.eclipse.leshan.server.send.SendListener;
import org.eclipse.leshan.transport.californium.server.endpoint.CaliforniumServerEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jline.internal.Log;
import jp.ac.meijo.ucl.extensions.client.UclabUnixDomainSocketClient;

/**
 * Leshanサーバのイベントを管理するServletクラス。 サーバー・セント・イベント (SSE) を利用してクライアントに各種イベント（登録、更新、解除、通知等）を送信します。
 */
public class EventServlet extends EventSourceServlet {

    // イベント名の定義
    private static final String EVENT_DEREGISTRATION = "DEREGISTRATION";
    private static final String EVENT_UPDATED = "UPDATED";
    private static final String EVENT_REGISTRATION = "REGISTRATION";
    private static final String EVENT_AWAKE = "AWAKE";
    private static final String EVENT_SLEEPING = "SLEEPING";
    private static final String EVENT_NOTIFICATION = "NOTIFICATION";
    private static final String EVENT_SEND = "SEND";
    private static final String EVENT_COAP_LOG = "COAPLOG";

    // クエリパラメータ名の定義（エンドポイント識別用）
    private static final String QUERY_PARAM_ENDPOINT = "ep";

    private static final long serialVersionUID = 1L;

    // ログ出力用ロガー
    private static final Logger LOG = LoggerFactory.getLogger(EventServlet.class);

    // JSONの変換・シリアライズ用オブジェクトマッパー
    private final ObjectMapper mapper;

    // CoAPメッセージをトレースするためのインスタンス
    private final CoapMessageTracer coapMessageTracer;

    // イベント送信先（クライアント）のコレクション。並列アクセスを考慮しConcurrentHashMapを利用
    private final Set<LeshanEventSource> eventSources = Collections
            .newSetFromMap(new ConcurrentHashMap<LeshanEventSource, Boolean>());

    // InfluxDBへデータを送信するためのUnixドメインソケットクライアント
    private final UclabUnixDomainSocketClient client;

    /**
     * デバイス登録、更新、解除イベントを監視し、各イベント発生時にクライアントへ通知を送信するリスナー
     */
    private final RegistrationListener registrationListener = new RegistrationListener() {

        @Override
        public void registered(Registration registration, Registration previousReg,
                Collection<Observation> previousObservations) {
            String jReg = null;

            // デバイス登録時にFONMシステムオブジェクトの監視を開始
            observeFONMSystemObjectsWhenRegistered(registration);

            try {
                // 登録情報をJSON形式に変換
                jReg = EventServlet.this.mapper.writeValueAsString(registration);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
            // 登録イベントをクライアントへ送信
            sendEvent(EVENT_REGISTRATION, jReg, registration.getEndpoint());
        }

        @Override
        public void updated(RegistrationUpdate update, Registration updatedRegistration,
                Registration previousRegistration) {
            RegUpdate regUpdate = new RegUpdate();
            regUpdate.registration = updatedRegistration;
            regUpdate.update = update;
            String jReg = null;
            try {
                // 更新情報をJSON形式に変換
                jReg = EventServlet.this.mapper.writeValueAsString(regUpdate);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
            // 更新イベントをクライアントへ送信
            sendEvent(EVENT_UPDATED, jReg, updatedRegistration.getEndpoint());
        }

        @Override
        public void unregistered(Registration registration, Collection<Observation> observations, boolean expired,
                Registration newReg) {
            String jReg = null;
            try {
                // 解除情報をJSON形式に変換
                jReg = EventServlet.this.mapper.writeValueAsString(registration);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
            // 解除イベントをクライアントへ送信
            sendEvent(EVENT_DEREGISTRATION, jReg, registration.getEndpoint());
        }

    };

    /**
     * デバイスのスリープ／ウェイクアップ状態を監視し、イベントをクライアントへ送信するリスナー
     */
    public final PresenceListener presenceListener = new PresenceListener() {

        @Override
        public void onSleeping(Registration registration) {
            // スリープ状態になった際、エンドポイントのみのJSON文字列を作成
            String data = new StringBuilder("{\"ep\":\"").append(registration.getEndpoint()).append("\"}").toString();
            sendEvent(EVENT_SLEEPING, data, registration.getEndpoint());
        }

        @Override
        public void onAwake(Registration registration) {
            // ウェイクアップ状態になった際、エンドポイントのみのJSON文字列を作成
            String data = new StringBuilder("{\"ep\":\"").append(registration.getEndpoint()).append("\"}").toString();
            sendEvent(EVENT_AWAKE, data, registration.getEndpoint());
        }
    };

    /**
     * オブザベーション（監視対象リソース）の通知を受け取り、クライアントへ送信するリスナー
     */
    private final ObservationListener observationListener = new ObservationListener() {

        @Override
        public void cancelled(Observation observation) {
            // 通知がキャンセルされた場合の処理（必要に応じて実装）
        }

        @Override
        public void onResponse(SingleObservation observation, Registration registration, ObserveResponse response) {

            if (LOG.isDebugEnabled()) {
                LOG.debug("Received notification from [{}] containing value [{}]", observation.getPath(),
                        response.getContent());
            }
            String jsonContent = null;
            try {
                // 通知内容をJSON形式に変換
                jsonContent = mapper.writeValueAsString(response.getContent());
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }

            // InfluxDBにデータを送信
            influxDB(jsonContent, response.getContent());

            if (registration != null) {
                // シングルオブザベーションの場合、エンドポイント、リソースパス、値をJSON形式で作成
                String data = new StringBuilder("{\"ep\":\"") //
                        .append(registration.getEndpoint()) //
                        .append("\",\"kind\":\"single\"") //
                        .append(",\"res\":\"") //
                        .append(observation.getPath()).append("\",\"val\":") //
                        .append(jsonContent) //
                        .append("}") //
                        .toString();

                // 通知イベントをクライアントへ送信
                sendEvent(EVENT_NOTIFICATION, data, registration.getEndpoint());
            }
        }

        @Override
        public void onResponse(CompositeObservation observation, Registration registration,
                ObserveCompositeResponse response) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Received composite notificationfrom [{}] containing value [{}]", response.getContent());
            }
            String jsonContent = null;
            String jsonListOfPath = null;
            try {
                // コンポジット通知の内容をJSON形式に変換
                jsonContent = mapper.writeValueAsString(response.getContent());
                List<String> paths = new ArrayList<String>();
                for (LwM2mPath path : response.getObservation().getPaths()) {
                    paths.add(path.toString());
                }
                jsonListOfPath = mapper.writeValueAsString(paths);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }

            if (registration != null) {
                // コンポジット通知の場合、エンドポイント、通知種別、値、パス情報をJSON形式で作成
                String data = new StringBuilder("{\"ep\":\"") //
                        .append(registration.getEndpoint()) //
                        .append("\",\"kind\":\"composite\"") //
                        .append(",\"val\":") //
                        .append(jsonContent) //
                        .append(",\"paths\":") //
                        .append(jsonListOfPath) //
                        .append("}") //
                        .toString();

                // 通知イベントをクライアントへ送信
                sendEvent(EVENT_NOTIFICATION, data, registration.getEndpoint());
            }
        }

        @Override
        public void onError(Observation observation, Registration registration, Exception error) {
            if (LOG.isWarnEnabled()) {
                LOG.warn(String.format("Unable to handle notification of [%s:%s]", observation.getRegistrationId(),
                        getObservationPaths(observation)), error);
            }
        }

        @Override
        public void newObservation(Observation observation, Registration registration) {
            // 新たなオブザベーションが開始された場合の処理（必要に応じて実装）
        }
    };

    /**
     * デバイスからの送信リクエストを受け取り、処理結果をクライアントへ送信するリスナー
     */
    private final SendListener sendListener = new SendListener() {

        @Override
        public void dataReceived(Registration registration, TimestampedLwM2mNodes data, SendRequest request) {

            if (LOG.isDebugEnabled()) {
                LOG.debug("Received Send request from [{}] containing value [{}]", registration, data.toString());
            }

            if (registration != null) {
                try {
                    // 最新のノード情報をJSON形式に変換
                    String jsonContent = EventServlet.this.mapper.writeValueAsString(data.getMostRecentNodes());

                    // 送信データのJSON文字列を作成
                    String eventData = new StringBuilder("{\"ep\":\"") //
                            .append(registration.getEndpoint()) //
                            .append("\",\"val\":") //
                            .append(jsonContent) //
                            .append("}") //
                            .toString();

                    // 送信イベントをクライアントへ送信
                    sendEvent(EVENT_SEND, eventData, registration.getEndpoint());
                } catch (JsonProcessingException e) {
                    Log.warn(String.format("Error while processing json [%s] : [%s]", data.toString(), e.getMessage()));
                }
            }
        }

        @Override
        public void onError(Registration registration, String errorMessage, Exception error) {
            if (LOG.isWarnEnabled()) {
                LOG.warn(String.format("Unable to handle Send Request from [%s] : %s.", registration, errorMessage),
                        error);
            }
        }
    };

    /**
     * オブザベーションからリソースパスを抽出するユーティリティメソッド
     *
     * @param observation オブザベーション
     * @return 対象のリソースパス（SingleObservationの場合は単一のパス、CompositeObservationの場合は複数のパス）
     */
    private String getObservationPaths(final Observation observation) {
        String path = null;
        if (observation instanceof SingleObservation) {
            path = ((SingleObservation) observation).getPath().toString();
        } else if (observation instanceof CompositeObservation) {
            path = ((CompositeObservation) observation).getPaths().toString();
        }
        return path;
    }

    /**
     * コンストラクタ
     * <p>
     * ・各種リスナー（登録、オブザベーション、プレゼンス、送信）をサーバに登録<br>
     * ・JSONシリアライザの設定を行う<br>
     * ・Unixドメインソケットクライアントの初期化と接続を行う
     * </p>
     *
     * @param server Leshanサーバ
     */
    public EventServlet(LeshanServer server) {
        // 各種リスナーの登録
        server.getRegistrationService().addListener(this.registrationListener);
        server.getObservationService().addListener(this.observationListener);
        server.getPresenceService().addListener(this.presenceListener);
        server.getSendService().addListener(this.sendListener);

        // 各エンドポイントに対して、CoAPメッセージをトレースするためのインターセプタを追加
        coapMessageTracer = new CoapMessageTracer(server.getRegistrationService());
        for (LwM2mServerEndpoint endpoint : server.getEndpoints()) {
            if (endpoint instanceof CaliforniumServerEndpoint)
                ((CaliforniumServerEndpoint) endpoint).getCoapEndpoint().addInterceptor(coapMessageTracer);
        }

        // JSONシリアライザの設定（null値は出力しない設定）
        ObjectMapper mapper = new ObjectMapper();
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        SimpleModule module = new SimpleModule();
        module.addSerializer(Link.class, new JacksonLinkSerializer());
        module.addSerializer(Registration.class, new JacksonRegistrationSerializer(server.getPresenceService()));
        module.addSerializer(RegistrationUpdate.class, new JacksonRegistrationUpdateSerializer());
        module.addSerializer(LwM2mNode.class, new JacksonLwM2mNodeSerializer());
        module.addSerializer(Version.class, new JacksonVersionSerializer());
        mapper.registerModule(module);
        this.mapper = mapper;

        // Unixドメインソケットクライアントの初期化と接続
        this.client = new UclabUnixDomainSocketClient();
        try {
            client.createSocket();
            client.connect();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 指定されたイベント情報を、対象のエンドポイントに接続している全てのクライアントへ送信する
     *
     * @param event イベント種別
     * @param data イベントデータ（JSON形式）
     * @param endpoint 対象エンドポイント（nullの場合は全クライアントへ送信）
     */
    public synchronized void sendEvent(String event, String data, String endpoint) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Dispatching {} event from endpoint {}", event, endpoint);
        }

        for (LeshanEventSource eventSource : eventSources) {
            if (eventSource.getEndpoint() == null || eventSource.getEndpoint().equals(endpoint)) {
                eventSource.sentEvent(event, data);
            }
        }
    }

    /**
     * CoAPメッセージを監視し、受信したメッセージをSSEイベントとしてクライアントへ送信するリスナー
     */
    class ClientCoapListener implements CoapMessageListener {

        private final String endpoint;

        ClientCoapListener(String endpoint) {
            this.endpoint = endpoint;
        }

        @Override
        public void trace(CoapMessage message) {
            try {
                // CoAPメッセージをJSON形式に変換し、エンドポイント情報を追加
                ObjectNode coapLog = EventServlet.this.mapper.valueToTree(message);
                coapLog.put("ep", this.endpoint);
                sendEvent(EVENT_COAP_LOG, EventServlet.this.mapper.writeValueAsString(coapLog), endpoint);
            } catch (JsonProcessingException e) {
                Log.warn(String.format("Error while processing json [%s] : [%s]", message.toString(), e.getMessage()));
                sendEvent(EVENT_COAP_LOG, message.toString(), endpoint);
            }
        }

    }

    /**
     * 指定されたエンドポイントに紐づくイベントソースがなくなった場合、CoAPリスナーを削除する
     *
     * @param endpoint エンドポイント
     */
    private void cleanCoapListener(String endpoint) {
        // エンドポイントに紐づくイベントソースが存在する場合は削除せず、存在しなければリスナーを除去する
        for (LeshanEventSource eventSource : eventSources) {
            if (eventSource.getEndpoint() == null || eventSource.getEndpoint().equals(endpoint)) {
                return;
            }
        }
        coapMessageTracer.removeListener(endpoint);
    }

    /**
     * HTTPリクエストから新たなEventSource（クライアント接続）を生成する。 リクエストパラメータ "ep" を利用してエンドポイントを特定する。
     *
     * @param req HttpServletRequest
     * @return EventSource インスタンス
     */
    @Override
    protected EventSource newEventSource(HttpServletRequest req) {
        String endpoint = req.getParameter(QUERY_PARAM_ENDPOINT);
        return new LeshanEventSource(endpoint);
    }

    /**
     * EventSourceの実装。クライアントとのSSE接続を管理する。
     */
    private class LeshanEventSource implements EventSource {

        private final String endpoint;
        private Emitter emitter;

        public LeshanEventSource(String endpoint) {
            this.endpoint = endpoint;
        }

        @Override
        public void onOpen(Emitter emitter) throws IOException {
            this.emitter = emitter;
            eventSources.add(this);

            if (endpoint != null) {
                // 指定されたエンドポイントに対して、CoAPメッセージリスナーを追加する
                coapMessageTracer.addListener(endpoint, new ClientCoapListener(endpoint));
            }

        }

        @Override
        public void onClose() {
            cleanCoapListener(endpoint);
            eventSources.remove(this);
        }

        /**
         * イベントを送信する
         *
         * @param event イベント名
         * @param data イベントデータ
         */
        public void sentEvent(String event, String data) {
            try {
                emitter.event(event, data);
            } catch (IOException e) {
                e.printStackTrace();
                onClose();
            }
        }

        public String getEndpoint() {
            return endpoint;
        }
    }

    /**
     * 登録更新イベントの情報を格納するための内部クラス
     */
    @SuppressWarnings("unused")
    private class RegUpdate {
        public Registration registration;
        public RegistrationUpdate update;
    }

    /**
     * 登録時にFONMシステムオブジェクト（ID: 32769）がサポートされている場合、監視開始のためのリクエストを送信する。
     * <p>
     * ※ 注意: 以下の実装では java.net.URL を使用してHTTPリクエストを送信していますが、Java 11以降では java.net.http.HttpClient を利用する方法が推奨されます。<br>
     * 例えば、以下のようなコードで代替可能です:
     *
     * <pre>
     * HttpClient httpClient = HttpClient.newHttpClient();
     * HttpRequest request = HttpRequest.newBuilder()
     *         .uri(URI.create("http://localhost:8080/api/clients/" + registration.getEndpoint() + "/32769/observe"))
     *         .POST(HttpRequest.BodyPublishers.noBody()).build();
     * HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
     * </pre>
     * </p>
     *
     * @param registration 登録情報
     */
    private void observeFONMSystemObjectsWhenRegistered(Registration registration) {
        Map<Integer, Version> map = registration.getSupportedObject();
        if (map.keySet().contains(32769)) {
            String file = "/api/clients/" + registration.getEndpoint() + "/32769/observe";
            try {
                // java.net.URL を利用してHTTPリクエストを作成し、POSTメソッドで送信
                URL url = new URL("http", "localhost", 8080, file);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                // リクエストメソッドをPOSTに設定
                conn.setRequestMethod("POST");
                // リクエストを送信し、レスポンスコードを取得
                conn.getResponseCode();
            } catch (IOException e) {
                // 例外発生時は特に処理を行わず
            }
        }
    }

    /**
     * InfluxDBへJSON形式のデータを送信するためのメソッド
     *
     * @param jsonContent JSON文字列（送信データ）
     * @param node 送信対象のLwM2mノード
     */
    private void influxDB(String jsonContent, LwM2mChildNode node) {
        if (!(node instanceof LwM2mObject))
            return;
        LwM2mObject obj = (LwM2mObject) node;

        // オブジェクトIDが32769の場合のみ送信処理を行う
        if (obj.getId() != 32769)
            return;

        // Unixドメインソケットクライアントが接続されていない場合、接続を試行する
        if (!client.isConnected()) {
            try {
                client.createSocket();
                client.connect();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        try {
            // JSON文字列をByteBufferに変換して送信し、レスポンスを待つ
            client.sendRequestAndWaitForResult(ByteBuffer.wrap(jsonContent.getBytes(StandardCharsets.UTF_8)));
        } catch (IOException | TimeoutException e) {
            Log.warn("Error while waiting response");
        }
    }
}
