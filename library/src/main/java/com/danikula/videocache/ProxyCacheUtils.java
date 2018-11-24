package com.danikula.videocache;

import android.text.TextUtils;
import android.webkit.MimeTypeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import static com.danikula.videocache.Preconditions.checkArgument;
import static com.danikula.videocache.Preconditions.checkNotNull;

/**
 * Just simple utils.
 *
 * @author Alexey Danilov (danikula@gmail.com).
 */
public class ProxyCacheUtils {

    private static final Logger LOG = LoggerFactory.getLogger("ProxyCacheUtils");
    static final int DEFAULT_BUFFER_SIZE = 8 * 1024;
    static final int MAX_ARRAY_PREVIEW = 16;

    static String getSupposablyMime(String url) {
        MimeTypeMap mimes = MimeTypeMap.getSingleton();
        String extension = MimeTypeMap.getFileExtensionFromUrl(url);
        return TextUtils.isEmpty(extension) ? null : mimes.getMimeTypeFromExtension(extension);
    }

    static void assertBuffer(byte[] buffer, long offset, int length) {
        checkNotNull(buffer, "Buffer must be not null!");
        checkArgument(offset >= 0, "Data offset must be positive!");
        checkArgument(length >= 0 && length <= buffer.length, "Length must be in range [0..buffer.length]");
    }

    static String preview(byte[] data, int length) {
        int previewLength = Math.min(MAX_ARRAY_PREVIEW, Math.max(length, 0));
        byte[] dataRange = Arrays.copyOfRange(data, 0, previewLength);
        String preview = Arrays.toString(dataRange);
        if (previewLength < length) {
            preview = preview.substring(0, preview.length() - 1) + ", ...]";
        }
        return preview;
    }

    /**
     * 对Url的字符内容 进行编码
     * @param url
     * @return
     */
    static String encode(String url) {
        try {
            // URLEncoder : 包含一个encode(String s,String enc)静态方法，它可以将普通字符串转换成application/x-www-form-urlencoded MIME字符串。
            return URLEncoder.encode(url, "utf-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException("Error encoding url", e);
        }
    }

    /**
     * 对Url的字符内容 进行解码
     * @param url
     * @return
     */
    static String decode(String url) {
        try {
            // URLDecoder : 包含一个decode的静态方法。可以将application/x-www-form-urlencoded MIME字符串转成普通字符串
            return URLDecoder.decode(url, "utf-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException("Error decoding url", e);
        }
    }

    static void close(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException e) {
                LOG.error("Error closing resource", e);
            }
        }
    }

    public static String computeMD5(String string) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("MD5");
            byte[] digestBytes = messageDigest.digest(string.getBytes());
            return bytesToHexString(digestBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String bytesToHexString(byte[] bytes) {
        StringBuffer sb = new StringBuffer();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
