package com.danikula.videocache.file;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * {@link DiskUsage} that uses LRU (Least Recently Used) strategy to trim cache.
 *
 * @author Alexey Danilov (danikula@gmail.com).
 */
public abstract class LruDiskUsage implements DiskUsage {

    private static final Logger LOG = LoggerFactory.getLogger("LruDiskUsage");
    private final ExecutorService workerThread = Executors.newSingleThreadExecutor();

    @Override
    public void touch(File file) throws IOException {
        workerThread.submit(new TouchCallable(file));
    }

    private void touchInBackground(File file) throws IOException {
        // 更新文件的最新修改时间
        Files.setLastModifiedNow(file);
        List<File> files = Files.getLruListFiles(file.getParentFile());
        trim(files);
    }

    protected abstract boolean accept(File file, long totalSize, int totalCount);

    /**
     * 遍历文件，将超出要求的文件，删除
     */
    private void trim(List<File> files) {
        long totalSize = countTotalSize(files);
        int totalCount = files.size();
        for (File file : files) {
            // 根据size或count，判断是否接收这个文件
            boolean accepted = accept(file, totalSize, totalCount);
            if (!accepted) {
                // 不接收，则删除文件
                long fileSize = file.length();
                boolean deleted = file.delete();
                if (deleted) {
                    totalCount--;
                    totalSize -= fileSize;
                    LOG.info("Cache file " + file + " is deleted because it exceeds cache limit");
                } else {
                    LOG.error("Error deleting file " + file + " for trimming cache");
                }
            }
        }
    }

    /**
     * 计算这些文件的总长度
     */
    private long countTotalSize(List<File> files) {
        long totalSize = 0;
        for (File file : files) {
            totalSize += file.length();
        }
        return totalSize;
    }

    private class TouchCallable implements Callable<Void> {

        private final File file;

        public TouchCallable(File file) {
            this.file = file;
        }

        @Override
        public Void call() throws Exception {
            touchInBackground(file);
            return null;
        }
    }
}
