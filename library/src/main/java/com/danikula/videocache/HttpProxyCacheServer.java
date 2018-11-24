package com.danikula.videocache;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.danikula.videocache.file.DiskUsage;
import com.danikula.videocache.file.FileNameGenerator;
import com.danikula.videocache.file.Md5FileNameGenerator;
import com.danikula.videocache.file.TotalCountLruDiskUsage;
import com.danikula.videocache.file.TotalSizeLruDiskUsage;
import com.danikula.videocache.headers.EmptyHeadersInjector;
import com.danikula.videocache.headers.HeaderInjector;
import com.danikula.videocache.sourcestorage.SourceInfoStorage;
import com.danikula.videocache.sourcestorage.SourceInfoStorageFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.danikula.videocache.Preconditions.checkAllNotNull;
import static com.danikula.videocache.Preconditions.checkNotNull;

/**
 * Simple lightweight proxy server with file caching support that handles HTTP requests.
 * Typical usage:
 * <pre><code>
 * public onCreate(Bundle state) {
 *      super.onCreate(state);
 *
 *      HttpProxyCacheServer proxy = getProxy();
 *      String proxyUrl = proxy.getProxyUrl(VIDEO_URL);
 *      videoView.setVideoPath(proxyUrl);
 * }
 *
 * private HttpProxyCacheServer getProxy() {
 * // should return single instance of HttpProxyCacheServer shared for whole app.
 * }
 * </code></pre>
 *
 * @author Alexey Danilov (danikula@gmail.com).
 */
public class HttpProxyCacheServer {

    private static final String TAG = HttpProxyCacheServer.class.getSimpleName();
    private static final Logger LOG = LoggerFactory.getLogger("HttpProxyCacheServer");
    private static final String PROXY_HOST = "127.0.0.1";

    private final Object clientsLock = new Object();
    private final ExecutorService socketProcessor = Executors.newFixedThreadPool(8);
    /**
     * 每一个 url 对应一个 HttpProxyCacheServerClients
     */
    private final Map<String, HttpProxyCacheServerClients> clientsMap = new ConcurrentHashMap<>();
    private final ServerSocket serverSocket;
    private final int port;
    private final Thread waitConnectionThread;
    private final Config config;
    private final Pinger pinger;

    public HttpProxyCacheServer(Context context) {
        this(new Builder(context).buildConfig());
    }

    private HttpProxyCacheServer(Config config) {
        this.config = checkNotNull(config);
        try {
            // 首先设置  127.0.0.1的本地代理服务socket  用于响应 播放器的多媒体数据请求业务
            InetAddress inetAddress = InetAddress.getByName(PROXY_HOST);
            // 建立一个端口号随机的ServerSocket，用于接收视频播放器的http请求
            // 0：指定服务器要绑定的端口（服务器要监听的端口），0表示由操作系统来为服务器分配一个任意可用的端口。
            // 8：指定客户连接请求队列的长度
            // inetAddress：指定服务器要绑定的IP地址。
            this.serverSocket = new ServerSocket(0, 8, inetAddress);
            // 保存Server代理服务器端口号
            this.port = serverSocket.getLocalPort();
            // 确保所有这类型的请求都不会走系统代理
            IgnoreHostProxySelector.install(PROXY_HOST, port);

            CountDownLatch startSignal = new CountDownLatch(1);
            // WaitRequestsRunnable中，新建一个死循环线程用于处理Socket连接
            this.waitConnectionThread = new Thread(new WaitRequestsRunnable(startSignal));
            this.waitConnectionThread.start();

            // 当前线程会被阻塞，直到CountDownLatch中的值为0。
            // 当 等待客户端请求的线程开启后，await方法返回
            startSignal.await(); // freeze thread, wait for server starts 用于等待Sever线程完成

            //
            this.pinger = new Pinger(PROXY_HOST, port);
            LOG.info("Proxy cache server started. Is it alive? " + isAlive());
        } catch (IOException | InterruptedException e) {
            socketProcessor.shutdown();
            throw new IllegalStateException("Error starting local proxy server", e);
        }
    }

    /**
     * Returns url that wrap original url and should be used for client (MediaPlayer, ExoPlayer, etc).
     * <p>
     * If file for this url is fully cached (it means method {@link #isCached(String)} returns {@code true})
     * then file:// uri to cached file will be returned.
     * <p>
     * Calling this method has same effect as calling {@link #getProxyUrl(String, boolean)} with 2nd parameter set to {@code true}.
     *
     * @param url a url to file that should be cached.
     * @return a wrapped by proxy url if file is not fully cached or url pointed to cache file otherwise.
     */
    public String getProxyUrl(String url) {
        return getProxyUrl(url, true);
    }

    /**
     * 将流数据源url地址转化为本地的代理服务器url，传递给播放器使用
     * Returns url that wrap original url and should be used for client (MediaPlayer, ExoPlayer, etc).
     * <p>
     * If parameter {@code allowCachedFileUri} is {@code true} and file for this url is fully cached
     * (it means method {@link #isCached(String)} returns {@code true}) then file:// uri to cached file will be returned.
     *
     * @param url                a url to file that should be cached.
     * @param allowCachedFileUri {@code true} if allow to return file:// uri if url is fully cached
     * @return a wrapped by proxy url if file is not fully cached or url pointed to cache file otherwise (if {@code allowCachedFileUri} is {@code true}).
     */
    public String getProxyUrl(String url, boolean allowCachedFileUri) {
        if (allowCachedFileUri && isCached(url)) {
            // 如果已经缓存过 这个 url，则获取缓存的文件，并根据这个文件生成新的url
            File cacheFile = getCacheFile(url);
            // 更新一下文件最后的修改时间，这是为了防止时间太久被Lru缓存清除
            touchFileSafely(cacheFile);
            // 如果url对应的媒体文件已经全部被缓存，则返回这个文件的Uri地址给播放器播放即可
            return Uri.fromFile(cacheFile).toString();
        }
        // 如果代理服务器在运行，就返回一个ProxyUrl，否则还是返回真实的Url给播放器播放
        return isAlive() ? appendToProxyUrl(url) : url;
    }

    public void registerCacheListener(CacheListener cacheListener, String url) {
        checkAllNotNull(cacheListener, url);
        synchronized (clientsLock) {
            try {
                getClients(url).registerCacheListener(cacheListener);
            } catch (ProxyCacheException e) {
                LOG.warn("Error registering cache listener", e);
            }
        }
    }

    public void unregisterCacheListener(CacheListener cacheListener, String url) {
        checkAllNotNull(cacheListener, url);
        synchronized (clientsLock) {
            try {
                getClients(url).unregisterCacheListener(cacheListener);
            } catch (ProxyCacheException e) {
                LOG.warn("Error registering cache listener", e);
            }
        }
    }

    public void unregisterCacheListener(CacheListener cacheListener) {
        checkNotNull(cacheListener);
        synchronized (clientsLock) {
            for (HttpProxyCacheServerClients clients : clientsMap.values()) {
                clients.unregisterCacheListener(cacheListener);
            }
        }
    }

    /**
     * Checks is cache contains fully cached file for particular url.
     *
     * @param url an url cache file will be checked for.
     * @return {@code true} if cache contains fully cached file for passed in parameters url.
     */
    public boolean isCached(String url) {
        checkNotNull(url, "Url can't be null!");
        return getCacheFile(url).exists();
    }

    public void shutdown() {
        LOG.info("Shutdown proxy server");

        shutdownClients();

        config.sourceInfoStorage.release();

        waitConnectionThread.interrupt();
        try {
            if (!serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            onError(new ProxyCacheException("Error shutting down proxy server", e));
        }
    }

    private boolean isAlive() {
        return pinger.ping(3, 70);   // 70+140+280=max~500ms
    }

    /**
     * ProxyUrl 生成逻辑非常简单，将原Url拼接到一个  http://127.0.0.1:xxx/Url 即可
     */
    private String appendToProxyUrl(String url) {
        return String.format(Locale.US, "http://%s:%d/%s", PROXY_HOST, port, ProxyCacheUtils.encode(url));
    }

    private File getCacheFile(String url) {
        File cacheDir = config.cacheRoot;
        String fileName = config.fileNameGenerator.generate(url);
        return new File(cacheDir, fileName);
    }

    private void touchFileSafely(File cacheFile) {
        try {
            config.diskUsage.touch(cacheFile);
        } catch (IOException e) {
            LOG.error("Error touching file " + cacheFile, e);
        }
    }

    private void shutdownClients() {
        synchronized (clientsLock) {
            for (HttpProxyCacheServerClients clients : clientsMap.values()) {
                clients.shutdown();
            }
            clientsMap.clear();
        }
    }

    private void waitForRequest() {
        try {
            // 如果当前线程 不被中断，则循环一致下去
            while (!Thread.currentThread().isInterrupted()) {
                // 阻塞线程，等待客户端的请求
                // 当 客户端 向服务器Socket发起请求时，accept方法会返回给客户端一个socket
                Socket socket = serverSocket.accept();
                Log.d(TAG, "waitForRequest:   Accept new socket " + socket);
                LOG.debug("Accept new socket " + socket);
                // 通过线程池处理每一个Socket连接
                socketProcessor.submit(new SocketProcessorRunnable(socket));
            }
        } catch (IOException e) {
            onError(new ProxyCacheException("Error during waiting connection", e));
        }
    }

    /**
     * 处理 请求的Socket
     *
     * @param socket
     */
    private void processSocket(Socket socket) {
        try {
            // 从服务器的Socket获取输入流，然后创建一个GetRequest
            GetRequest request = GetRequest.read(socket.getInputStream());
            LOG.debug("Request to cache proxy:" + request);
            Log.d(TAG, "processSocket: request = " + request);
            // 对request.uri 进行接 解码
            String url = ProxyCacheUtils.decode(request.uri);
            Log.d(TAG, "processSocket: url = " + url);
            if (pinger.isPingRequest(url)) {
                // 如果是视频播放器发出了一个ping请求，直接返回 200 ok
                pinger.responseToPing(socket);
            } else {
                // 通过url获取一个处理的Client
                // 通过url获取（如果没有就 new 一个client）
                HttpProxyCacheServerClients clients = getClients(url);
                // 通过Client处理请求request，socket
                clients.processRequest(request, socket);
            }
        } catch (SocketException e) {
            // There is no way to determine that client closed connection http://stackoverflow.com/a/10241044/999458
            // So just to prevent log flooding don't log stacktrace
            LOG.debug("Closing socket… Socket is closed by client.");
        } catch (ProxyCacheException | IOException e) {
            onError(new ProxyCacheException("Error processing request", e));
        } finally {
            // 关闭socket
            releaseSocket(socket);
            LOG.debug("Opened connections: " + getClientsCount());
        }
    }

    /**
     * 通过url获取（如果没有就 new 一个client）
     *
     * @param url
     * @return
     * @throws ProxyCacheException
     */
    private HttpProxyCacheServerClients getClients(String url) throws ProxyCacheException {
        synchronized (clientsLock) {
            // 从clientsMap中获取
            HttpProxyCacheServerClients clients = clientsMap.get(url);
            if (clients == null) {
                // clientsMap中没有与该url对应的HttpProxyCacheServerClients对象，则new一个新的，并将其加入到clientsMap中。
                clients = new HttpProxyCacheServerClients(url, config);
                clientsMap.put(url, clients);
            }
            return clients;
        }
    }

    private int getClientsCount() {
        synchronized (clientsLock) {
            int count = 0;
            for (HttpProxyCacheServerClients clients : clientsMap.values()) {
                count += clients.getClientsCount();
            }
            return count;
        }
    }

    private void releaseSocket(Socket socket) {
        closeSocketInput(socket);
        closeSocketOutput(socket);
        closeSocket(socket);
    }

    private void closeSocketInput(Socket socket) {
        try {
            if (!socket.isInputShutdown()) {
                socket.shutdownInput();
            }
        } catch (SocketException e) {
            // There is no way to determine that client closed connection http://stackoverflow.com/a/10241044/999458
            // So just to prevent log flooding don't log stacktrace
            LOG.debug("Releasing input stream… Socket is closed by client.");
        } catch (IOException e) {
            onError(new ProxyCacheException("Error closing socket input stream", e));
        }
    }

    private void closeSocketOutput(Socket socket) {
        try {
            if (!socket.isOutputShutdown()) {
                socket.shutdownOutput();
            }
        } catch (IOException e) {
            LOG.warn("Failed to close socket on proxy side: {}. It seems client have already closed connection.", e.getMessage());
        }
    }

    private void closeSocket(Socket socket) {
        try {
            if (!socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            onError(new ProxyCacheException("Error closing socket", e));
        }
    }

    private void onError(Throwable e) {
        LOG.error("HttpProxyCacheServer error", e);
    }

    /**
     * 该Runnable的作用是：开启一个死循环，等待客户端连接
     */
    private final class WaitRequestsRunnable implements Runnable {

        private final CountDownLatch startSignal;

        public WaitRequestsRunnable(CountDownLatch startSignal) {
            this.startSignal = startSignal;
        }

        @Override
        public void run() {
            startSignal.countDown();
            waitForRequest();
        }
    }

    /**
     * 处理客户端的Socket连接
     */
    private final class SocketProcessorRunnable implements Runnable {

        /**
         * 代理服务器Socket向客户端返回的Socket
         */
        private final Socket socket;

        public SocketProcessorRunnable(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            processSocket(socket);
        }
    }

    /**
     * Builder for {@link HttpProxyCacheServer}.
     */
    public static final class Builder {

        // 512M
        private static final long DEFAULT_MAX_SIZE = 512 * 1024 * 1024;

        private File cacheRoot;
        private FileNameGenerator fileNameGenerator;
        private DiskUsage diskUsage;
        /**
         * SourceInfo 存储的类对象
         */
        private SourceInfoStorage sourceInfoStorage;
        //
        private HeaderInjector headerInjector;

        public Builder(Context context) {
            // 初始化 SourceInfo 存储的 类对象
            this.sourceInfoStorage = SourceInfoStorageFactory.newSourceInfoStorage(context);

            // 获取默认的可用缓存目录
            this.cacheRoot = StorageUtils.getIndividualCacheDirectory(context);

            // 缓存文件的最大size是 512M
            this.diskUsage = new TotalSizeLruDiskUsage(DEFAULT_MAX_SIZE);

            //
            this.fileNameGenerator = new Md5FileNameGenerator();
            //
            this.headerInjector = new EmptyHeadersInjector();
        }

        /**
         * Overrides default cache folder to be used for caching files.
         * <p>
         * By default AndroidVideoCache uses
         * '/Android/data/[app_package_name]/cache/video-cache/' if card is mounted and app has appropriate permission
         * or 'video-cache' subdirectory in default application's cache directory otherwise.
         * </p>
         * <b>Note</b> directory must be used <b>only</b> for AndroidVideoCache files.
         *
         * @param file a cache directory, can't be null.
         * @return a builder.
         */
        public Builder cacheDirectory(File file) {
            this.cacheRoot = checkNotNull(file);
            return this;
        }

        /**
         * Overrides default cache file name generator {@link Md5FileNameGenerator} .
         *
         * @param fileNameGenerator a new file name generator.
         * @return a builder.
         */
        public Builder fileNameGenerator(FileNameGenerator fileNameGenerator) {
            this.fileNameGenerator = checkNotNull(fileNameGenerator);
            return this;
        }

        /**
         * Sets max cache size in bytes.
         * <p>
         * All files that exceeds limit will be deleted using LRU strategy.
         * Default value is 512 Mb.
         * </p>
         * Note this method overrides result of calling {@link #maxCacheFilesCount(int)}
         *
         * @param maxSize max cache size in bytes.
         * @return a builder.
         */
        public Builder maxCacheSize(long maxSize) {
            this.diskUsage = new TotalSizeLruDiskUsage(maxSize);
            return this;
        }

        /**
         * Sets max cache files count.
         * All files that exceeds limit will be deleted using LRU strategy.
         * Note this method overrides result of calling {@link #maxCacheSize(long)}
         *
         * @param count max cache files count.
         * @return a builder.
         */
        public Builder maxCacheFilesCount(int count) {
            this.diskUsage = new TotalCountLruDiskUsage(count);
            return this;
        }

        /**
         * Set custom DiskUsage logic for handling when to keep or clean cache.
         *
         * @param diskUsage a disk usage strategy, cant be {@code null}.
         * @return a builder.
         */
        public Builder diskUsage(DiskUsage diskUsage) {
            this.diskUsage = checkNotNull(diskUsage);
            return this;
        }

        /**
         * Add headers along the request to the server
         *
         * @param headerInjector to inject header base on url
         * @return a builder
         */
        public Builder headerInjector(HeaderInjector headerInjector) {
            this.headerInjector = checkNotNull(headerInjector);
            return this;
        }

        /**
         * Builds new instance of {@link HttpProxyCacheServer}.
         *
         * @return proxy cache. Only single instance should be used across whole app.
         */
        public HttpProxyCacheServer build() {
            Config config = buildConfig();
            return new HttpProxyCacheServer(config);
        }

        private Config buildConfig() {
            return new Config(cacheRoot, fileNameGenerator, diskUsage, sourceInfoStorage, headerInjector);
        }

    }
}
