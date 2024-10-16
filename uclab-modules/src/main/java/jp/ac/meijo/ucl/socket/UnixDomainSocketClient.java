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

package jp.ac.meijo.ucl.socket;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.NotYetConnectedException;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * UnixドメインソケットクライアントクラスはUNIXドメインソケットを使用して通信を行うためのクラスです。
 * このクラスはAutoCloseableインターフェースを実装し、try-with-resourcesステートメントで使用できます。
 */
public class UnixDomainSocketClient implements AutoCloseable {
    private static final String SOCKET_PATH = "/tmp/uds_socket";
    private static final int BUFFER_SIZE = 1024; // バッファサイズを定義
    private static final long DEFAULT_TIMEOUT = 10000;

    private SocketChannel socketChannel;
    private UnixDomainSocketAddress address;
    private ExecutorService executorService;

    private volatile boolean isConnected = false;

    /**
     * INFO : 各メソッドの開始時や重要なイベント DEBUG : INFOより詳細な操作や状態変更 ERROR : エラーが発生する可能性がある箇所
     */
    private static final Logger LOG = LoggerFactory.getLogger(UnixDomainSocketClient.class);

    /**
     * UnixDomainSocketClientのコンストラクタ。 シングルスレッドの ExecutorService を初期化します。
     */
    public UnixDomainSocketClient() {
        LOG.info("Initializing UnixDomainSocketClient");
        this.executorService = Executors.newSingleThreadExecutor();
        LOG.debug("ExecutorService initialized");

        Runtime.getRuntime().addShutdownHook(new Thread(this::close));
    }

    /**
     * UNIXドメインソケットを作成します。
     *
     * @throws IOException
     *             ソケットの作成に失敗した場合
     */
    public void createSocket() throws IOException {
        LOG.info("Creating Unix domain socket");
        socketChannel = SocketChannel.open(StandardProtocolFamily.UNIX);
        Path socketPath = Path.of(SOCKET_PATH);
        address = UnixDomainSocketAddress.of(socketPath);
        LOG.debug("Socket created with path: {}", SOCKET_PATH);
    }

    /**
     * サーバーに接続します。
     *
     * @throws IllegalStateException
     *             ソケットが作成されていない場合
     * @throws IOException
     *             接続に失敗した場合
     */
    public void connect() throws IllegalStateException, IOException {
        if (address == null) {
            LOG.error("Socket not created. Call createSocket() first.");
            throw new IllegalStateException("Socket not created. Call createSocket() first.");
        }
        LOG.info("Connecting to server");
        if (socketChannel.connect(address)) {
            isConnected = true;
            LOG.info("Connected to server successfully");
        } else {
            LOG.info("Connecting failed");
        }

    }

    /**
     * リソースを解放し、ソケットとExecutorServiceを閉じます。
     */
    @Override
    public void close() {
        if (!isConnected) {
            return; // 既に閉じている場合は何もしない
        }

        LOG.info("Closing UnixDomainSocketClient");
        isConnected = false;

        try {
            if (socketChannel != null) {
                socketChannel.close();
                LOG.debug("SocketChannel closed");
            }
        } catch (IOException e) {
            LOG.error("Error closing socket", e);
        }

        // ExecutorServiceをシャットダウン
        if (executorService != null) {
            executorService.shutdownNow();
            try {
                if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    LOG.warn("ExecutorService did not terminate");
                }
            } catch (InterruptedException e) {
                LOG.error("Interrupted while waiting for ExecutorService termination", e);
                Thread.currentThread().interrupt();
            }
            LOG.debug("ExecutorService shut down");
        }
    }

    /**
     * ソケットの接続状態を返します。
     *
     * @return ソケットが接続済みの場合はtrue、それ以外の場合はfalse
     */
    public boolean isConnected() {
        return isConnected;
    }

    /**
     * データをサーバーに書き込みます。
     *
     * @param bb
     *            書き込むデータを含むByteBuffer
     *
     * @throws NotYetConnectedException
     *             ソケットが接続されていない場合
     * @throws IOException
     *             書き込み中にエラーが発生した場合
     */
    public void write(ByteBuffer bb) throws NotYetConnectedException, IOException {
        if (!isConnected) {
            LOG.error("Socket is not connected");
            throw new NotYetConnectedException();
        }
        int bytesWritten = socketChannel.write(bb);
        LOG.debug("Wrote {} bytes to server", bytesWritten);
    }

    /**
     * サーバーからデータを非同期に読み取ります。
     *
     * @return 読み取ったデータを含むByteBufferのFuture
     *
     * @throws NotYetConnectedException
     *             ソケットが接続されていない場合
     */
    public Future<ByteBuffer> read() throws NotYetConnectedException {
        if (!isConnected) {
            LOG.error("Socket is not connected");
            throw new NotYetConnectedException();
        }
        LOG.debug("Submitting read task to ExecutorService");
        return executorService.submit(new CallableTask());
    }

    /**
     * サーバーにリクエストを送信し、処理結果を待ち受けます。 このメソッドは、リクエストの送信から結果の受信までを一連の操作として実行します。 タイムアウトを設定し、指定された時間内に結果が返ってこない場合は例外をスローします。
     *
     * @param request
     *            サーバーに送信するリクエストデータを含むByteBuffer
     * @param timeout
     *            結果を待つ最大時間（ミリ秒）
     *
     * @return サーバーでの処理が成功した場合はtrue、失敗した場合はfalse
     *
     * @throws IOException
     *             通信中にエラーが発生した場合
     * @throws NotYetConnectedException
     *             ソケットが接続されていない場合
     * @throws TimeoutException
     *             指定された時間内に結果が返ってこなかった場合
     */
    public boolean sendRequestAndWaitForResult(ByteBuffer request, long timeout)
            throws IOException, NotYetConnectedException, TimeoutException {
        if (!isConnected) {
            LOG.error("Socket is not connected");
            throw new NotYetConnectedException();
        }

        // リクエストを送信
        write(request);
        LOG.debug("Request sent, waiting for result");

        // 結果を受信
        ByteBuffer resultBuffer = ByteBuffer.allocate(1);
        Future<ByteBuffer> futureResult = read();

        try {
            resultBuffer = futureResult.get(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.error("Interrupted while waiting for result", e);
            throw new IOException("Interrupted while waiting for result", e);
        } catch (ExecutionException e) {
            LOG.error("Error occurred while reading result", e);
            throw new IOException("Error occurred while reading result", e.getCause());
        } catch (TimeoutException e) {
            LOG.error("Timeout occurred while waiting for result");
            throw new TimeoutException();
        }

        if (resultBuffer.remaining() == 0) {
            LOG.error("No data received from server");
            throw new IOException("No data received from server");
        }

        boolean success = resultBuffer.get() == 1;
        LOG.info("Received result from server: {}", success ? "Success" : "Failure");

        return success;
    }

    /**
     * デフォルトのタイムアウトでリクエストを送信し、結果を待ち受けます。
     *
     * @param request
     *            サーバーに送信するリクエストデータを含むByteBuffer
     *
     * @return サーバーでの処理が成功した場合はtrue、失敗した場合はfalse
     *
     * @throws IOException
     *             通信中にエラーが発生した場合
     * @throws NotYetConnectedException
     *             ソケットが接続されていない場合
     * @throws TimeoutException
     *             指定された時間内に結果が返ってこなかった場合
     */
    public boolean sendRequestAndWaitForResult(ByteBuffer request)
            throws IOException, NotYetConnectedException, TimeoutException {
        return sendRequestAndWaitForResult(request, DEFAULT_TIMEOUT);
    }

    /**
     * サーバーからデータを読み取るためのCallableタスク。
     */
    private class CallableTask implements Callable<ByteBuffer> {
        @Override
        public ByteBuffer call() throws IOException {
            LOG.debug("Reading data from server");
            ByteBuffer bb = ByteBuffer.allocate(BUFFER_SIZE);
            int bytesRead = socketChannel.read(bb);
            bb.flip();
            LOG.debug("Read {} bytes from server", bytesRead);
            return bb;
        }
    }
}
