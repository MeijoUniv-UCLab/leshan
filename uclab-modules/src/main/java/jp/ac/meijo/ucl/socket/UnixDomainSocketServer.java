/*******************************************************************************
 * Copyright (c) 2024 Kengo Shimizu and UCLab.
 *
 * Contributors:
 *     Kengo Shimizu - implementation
 *******************************************************************************/

 package jp.ac.meijo.ucl.socket;

 import java.io.IOException;
 import java.net.StandardProtocolFamily;
 import java.net.UnixDomainSocketAddress;
 import java.nio.ByteBuffer;
 import java.nio.channels.ServerSocketChannel;
 import java.nio.channels.SocketChannel;
 import java.nio.file.Files;
 import java.nio.file.Path;
 import java.util.ArrayList;
 import java.util.List;
 import java.util.concurrent.ExecutorService;
 import java.util.concurrent.Executors;
 import java.util.concurrent.TimeUnit;
 
 import org.slf4j.Logger;
 import org.slf4j.LoggerFactory;
 
 public abstract class UnixDomainSocketServer implements AutoCloseable {
     protected static final String SOCKET_PATH = "/tmp/uds_socket";
     protected static final int BUFFER_SIZE = 1024;
     protected ServerSocketChannel serverChannel;
     protected Path socketPath;
     protected List<SocketChannel> channelList;
     protected ExecutorService recvThreadPool;
     
     private volatile boolean isRunning = false;
     
     /**
      * INFO : 各メソッドの開始時や重要なイベント
      * DEBUG : INFOより詳細な操作や状態変更
      * ERROR : エラーが発生する可能性がある箇所
      */
     protected static final Logger LOG = LoggerFactory.getLogger(UnixDomainSocketServer.class);
     
     /**
      * サーバーを起動し、必要なリソースを初期化します。
      * @throws IOException ソケットの作成や削除に失敗した場合
      */
     public void start() throws IOException {
         LOG.info("Starting Unix Domain Socket Server");
         socketPath = Path.of(SOCKET_PATH);
         Files.deleteIfExists(socketPath);
         serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
         UnixDomainSocketAddress address = UnixDomainSocketAddress.of(socketPath);
         serverChannel.bind(address);
         channelList = new ArrayList<>();
         recvThreadPool = Executors.newCachedThreadPool();
         isRunning = true;
 
         Runtime.getRuntime().addShutdownHook(new Thread(this::close));
         
         LOG.info("Server started successfully");
     }
 
     /**
      * クライアントからの接続を待ち受け、接続を処理します。
      */
     public void listen() {
         LOG.info("Server listening for connections");
         try { 
             while (isRunning && serverChannel.isOpen()) {
                 SocketChannel clientChannel = serverChannel.accept();
                 LOG.info("New client connected: {}", clientChannel.getRemoteAddress());
                 channelList.add(clientChannel);
                 recvThreadPool.submit(() -> handleClientConnection(clientChannel));
             }
         } catch (IOException e) {
             LOG.error("Error while listening for connections", e);
         } finally {
             close();
         }
     }
     
     /**
      * サーバーのリソースを解放します。
      */
     @Override
     public void close() {
         if (!isRunning) {
             return; // 既に閉じている場合は何もしない
         }
         
         LOG.info("Closing server and releasing resources");
         isRunning = false;
         
         channelList.forEach(this::closeClientChannel);
         try {
             if (serverChannel != null) {
                 serverChannel.close();
                 LOG.info("Server channel closed");
             }
             Files.deleteIfExists(socketPath);
             LOG.info("Socket file deleted: {}", socketPath);
         } catch (IOException e) {
             LOG.error("Failed to clean up resources", e);
         }
         // スレッドプールをシャットダウン
         if (recvThreadPool != null) {
             recvThreadPool.shutdownNow();
             try {
                 if (!recvThreadPool.awaitTermination(5, TimeUnit.SECONDS)) {
                     LOG.warn("Thread pool did not terminate");
                 }
             } catch (InterruptedException e) {
                 LOG.error("Interrupted while waiting for thread pool termination", e);
                 Thread.currentThread().interrupt();
             }
             LOG.info("Receive thread pool shut down");
         }
     }
 
     /**
      * クライアントチャンネルを閉じ、リストから削除します。
      * @param clientChannel 閉じるクライアントチャンネル
      */
     public void closeClientChannel(SocketChannel clientChannel) {
         try {
             clientChannel.close();
             channelList.remove(clientChannel);
             LOG.info("Closed client channel: {}", clientChannel);
         } catch (IOException e) {
             LOG.error("Error closing client channel", e);
         } finally {
             channelList.remove(clientChannel);
         }
     }
 
     /**
      * クライアントチャンネルにデータを書き込みます。
      * @param clientChannel 書き込み先のクライアントチャンネル
      * @param bb 書き込むデータを含むByteBuffer
      */
     public void write(SocketChannel clientChannel, ByteBuffer bb) {
         if (clientChannel.isConnected()) {
             try {
                 int bytesWritten = clientChannel.write(bb);
                 LOG.debug("Wrote {} bytes to client channel: {}", bytesWritten, clientChannel);
             } catch (IOException e) {
                 LOG.error("Error writing to client channel", e);
                 closeClientChannel(clientChannel);
             }
         }
     }
 
     /**
      * クライアントチャンネルからデータを読み込みます。
      * @param clientChannel 読み込み元のクライアントチャンネル
      * @return 読み込んだデータを含むByteBuffer
      * @throws IOException 読み込み中にエラーが発生した場合
      */
     public ByteBuffer read(SocketChannel clientChannel) throws IOException {
         ByteBuffer bb = ByteBuffer.allocate(BUFFER_SIZE);
         int bytesRead = clientChannel.read(bb);
         if (bytesRead == -1) {
             LOG.info("End of stream reached for client channel: {}", clientChannel);
             throw new IOException("End of stream reached");
         }
         bb.flip();
         LOG.debug("Read {} bytes from client channel: {}", bytesRead, clientChannel);
         return bb;
     }
     
     /**
      * クライアントの接続を処理し、リクエストを処理して結果を送信します。
      * このメソッドは、クライアントからの接続ごとに呼び出されます。
      *
      * @param clientChannel クライアントとの通信に使用するSocketChannel
      */
     protected void handleClientConnection(SocketChannel clientChannel) {
         try {
             while(clientChannel.isConnected()) {
                 boolean result = processClientRequest(clientChannel);
                 sendResult(clientChannel, result);
             }
         } catch (IOException e) {
             LOG.error("Error handling client connection", e);
             try {
                 sendResult(clientChannel, false);
             } catch (IOException ex) {
                 LOG.error("Failed to send error result to client", ex);
             }
         }
     }
     
     /**
      * クライアントからのリクエストを処理する抽象メソッド。
      * このメソッドは、具体的なサーバー実装クラスでオーバーライドする必要があります。
      *
      * @param clientChannel クライアントとの通信に使用するSocketChannel
      * @return リクエストの処理が成功した場合はtrue、失敗した場合はfalse
      * @throws IOException 入出力処理中にエラーが発生した場合
      */
     protected abstract boolean processClientRequest(SocketChannel clientChannel) throws IOException;
     
     /**
      * 処理結果をクライアントに送信します。
      *
      * @param clientChannel クライアントとの通信に使用するSocketChannel
      * @param success 処理が成功した場合はtrue、失敗した場合はfalse
      * @throws IOException 結果の送信中にエラーが発生した場合
      */
     protected void sendResult(SocketChannel clientChannel, boolean success) throws IOException {
         ByteBuffer resultBuffer = ByteBuffer.allocate(1);
         resultBuffer.put(success ? (byte) 1 : (byte) 0);
         resultBuffer.flip();
         clientChannel.write(resultBuffer);
         LOG.info("Sent result to client: {}", success ? "Success" : "Failure");
     }
     
 }