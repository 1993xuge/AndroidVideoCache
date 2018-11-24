package com.danikula.videocache.sourcestorage;

import android.content.Context;

/**
 * Simple factory for {@link SourceInfoStorage}.
 *
 * @author Alexey Danilov (danikula@gmail.com).
 */
public class SourceInfoStorageFactory {

    /**
     * 创建 通过数据库存储 SourceInfo 的 DatabaseSourceInfoStorage类对象
     * @param context
     * @return
     */
    public static SourceInfoStorage newSourceInfoStorage(Context context) {
        return new DatabaseSourceInfoStorage(context);
    }

    /**
     * 创建一个 空的实现的 NoSourceInfoStorage类对象
     * @return
     */
    public static SourceInfoStorage newEmptySourceInfoStorage() {
        return new NoSourceInfoStorage();
    }
}
