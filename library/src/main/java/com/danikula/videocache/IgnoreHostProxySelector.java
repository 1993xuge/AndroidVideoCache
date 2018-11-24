package com.danikula.videocache;

import android.util.Log;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.List;

import static com.danikula.videocache.Preconditions.checkNotNull;

/**
 * {@link ProxySelector} that ignore system default proxies for concrete host.
 * <p>
 * It is important to <a href="https://github.com/danikula/AndroidVideoCache/issues/28">ignore system proxy</a> for localhost connection.
 * 自动选择代理
 * @author Alexey Danilov (danikula@gmail.com).
 */
class IgnoreHostProxySelector extends ProxySelector {

    private static final String TAG = IgnoreHostProxySelector.class.getSimpleName();

    private static final List<Proxy> NO_PROXY_LIST = Arrays.asList(Proxy.NO_PROXY);

    /**
     * 系统 默认的代理服务器
     */
    private final ProxySelector defaultProxySelector;
    /**
     * 需要拦截的url的host name
     */
    private final String hostToIgnore;
    /**
     * 需要拦截的url的
     */
    private final int portToIgnore;

    IgnoreHostProxySelector(ProxySelector defaultProxySelector, String hostToIgnore, int portToIgnore) {
        this.defaultProxySelector = checkNotNull(defaultProxySelector);
        this.hostToIgnore = checkNotNull(hostToIgnore);
        this.portToIgnore = portToIgnore;
    }

    static void install(String hostToIgnore, int portToIgnore) {
        // 获取 目前的默认代理选择器
        ProxySelector defaultProxySelector = ProxySelector.getDefault();
        // 创建 自定义的代理选择器
        ProxySelector ignoreHostProxySelector = new IgnoreHostProxySelector(defaultProxySelector, hostToIgnore, portToIgnore);
        // 将其设置为 系统默认的代理选择器
        ProxySelector.setDefault(ignoreHostProxySelector);
    }

    /**
     * 给定一个URI返回一个最适合访问该URI的代理服务器列表，
     * 其中会首选列表的第一个代理，如果不行则用第二个，如果全部不行就会调用connectFailed方法处理连接失败问题.
     */
    @Override
    public List<Proxy> select(URI uri) {
        Log.d(TAG, "select: uri = " + uri.toString());
        // 如果 uri的host name和端口号，与需要拦截的一致，那么就使用代理，而非向远程服务器请求
        // 也就是 缓存了的 url，直接使用。未缓存过的，向远程服务器请求
        boolean ignored = hostToIgnore.equals(uri.getHost()) && portToIgnore == uri.getPort();
        return ignored ? NO_PROXY_LIST : defaultProxySelector.select(uri);
    }

    /**
     * 当select方法 返回的 URI的代理服务器列表，全都访问失败时，回调connectFailed方法，处理链接失败
     *
     * @param uri     - URI是目标网址；
     * @param address - sa是连接失败的那个Proxy的IP地址；
     * @param failure - ioe是连接失败时所抛出的异常对象；
     */
    @Override
    public void connectFailed(URI uri, SocketAddress address, IOException failure) {
        Log.d(TAG, "connectFailed: uri = " + uri.toString());
        Log.d(TAG, "connectFailed: IOException = " + failure.toString());
        // 使用 代理服务器 连接失败，则使用默认的代理向远端服务器请求数据
        defaultProxySelector.connectFailed(uri, address, failure);
    }
}
