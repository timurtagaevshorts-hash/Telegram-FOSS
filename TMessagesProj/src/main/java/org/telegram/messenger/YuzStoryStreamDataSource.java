package org.telegram.messenger;

import android.net.Uri;
import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stories;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * YuzGram: Telegram hikoyasi videosini media3 ExoPlayer'ga OQIM sifatida
 * beradigan DataSource. Telegram'ning o'z [FileStreamLoadOperation]'i bilan
 * bir xil g'oya (FileLoader stream API), lekin media3 turlari bilan —
 * shu sababli hikoya to'liq yuklanishini kutmasdan ijro boshlanadi.
 *
 * Fayl [FileLoader.loadStreamFile] orqali attachment papkasiga yoziladi;
 * o'qish faqat serverdan kelgan bo'laklardan amalga oshiriladi, qolganida
 * [FileLoadOperationStream.newDataAvailable] signalini kutadi. Kutish
 * chegaralangan: yuklash xato bo'lsa (qurilma oflayn, fayl o'chirilgan)
 * ExoPlayer abadiy osilib qolmasligi uchun IOException qaytadi — adapter
 * o'zining retry/error yo'lini ishga tushiradi.
 */
public class YuzStoryStreamDataSource implements DataSource, FileLoadOperationStream {

    /** Bitta ruxsat etilgan "progresssiz" kutish (ms). */
    private static final long STALL_WAIT_MS = 10_000L;
    /** Shu vaqt ichida birorta yangi bayt kelmasa — oqim o'lik. */
    private static final long STALL_TIMEOUT_MS = 45_000L;

    private final int account;
    private final TL_stories.StoryItem parentObject;
    @Nullable
    private TransferListener transferListener;
    @Nullable
    private DataSpec dataSpec;

    private FileLoadOperation loadOperation;
    private Uri uri;
    private long bytesRemaining = C.LENGTH_UNSET;
    private boolean opened;
    private long currentOffset;
    private CountDownLatch countDownLatch;
    private RandomAccessFile file;
    private TLRPC.Document document;
    private File currentFile;

    public YuzStoryStreamDataSource(int account, TL_stories.StoryItem parentObject) {
        this.account = account;
        this.parentObject = parentObject;
    }

    public static Uri buildUri(long dialogId, int storyId) {
        return Uri.parse("yuzstory://" + dialogId + "/" + storyId);
    }

    @Override
    public void addTransferListener(TransferListener transferListener) {
        this.transferListener = transferListener;
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        this.dataSpec = dataSpec;
        uri = dataSpec.uri;
        if (parentObject == null || parentObject.media == null) {
            throw new IOException("story media missing");
        }
        document = parentObject.media.getDocument();
        if (document == null) {
            throw new IOException("story document missing");
        }
        FileLoader loader = FileLoader.getInstance(account);
        if (parentObject.fileReference == 0) {
            parentObject.fileReference = loader.getFileReference(parentObject);
        }
        currentOffset = dataSpec.position;
        loadOperation = loader.loadStreamFile(
                this, document, null, parentObject, currentOffset, false, FileLoader.PRIORITY_HIGH
        );
        if (loadOperation == null) {
            throw new IOException("stream operation unavailable");
        }
        if (dataSpec.length == C.LENGTH_UNSET) {
            bytesRemaining = document.size > 0 ? document.size - dataSpec.position : C.LENGTH_UNSET;
        } else {
            bytesRemaining = dataSpec.length;
        }
        if (bytesRemaining < 0 && bytesRemaining != C.LENGTH_UNSET) {
            throw new EOFException();
        }
        opened = true;
        if (transferListener != null) {
            transferListener.onTransferStart(this, dataSpec, false);
        }
        syncFile(loadOperation.isFinished());
        return bytesRemaining;
    }

    @Override
    public int read(byte[] buffer, int offset, int readLength) throws IOException {
        if (readLength == 0) {
            return 0;
        }
        if (bytesRemaining == 0) {
            return C.RESULT_END_OF_INPUT;
        }
        if (!opened || loadOperation == null) {
            return C.RESULT_END_OF_INPUT;
        }
        int bytesRead;
        try {
            int targetLength = readLength;
            if (bytesRemaining > 0 && bytesRemaining < targetLength) {
                targetLength = (int) bytesRemaining;
            }
            int availableLength = 0;
            long progressDeadline = SystemClock.elapsedRealtime() + STALL_TIMEOUT_MS;
            while (true) {
                long[] downloaded = loadOperation.getDownloadedLengthFromOffset(currentOffset, targetLength);
                availableLength = (int) downloaded[0];
                boolean finished = downloaded[1] != 0 || loadOperation.isFinished();
                syncFile(finished);
                if (availableLength > 0 && file != null) {
                    break;
                }
                if (finished) {
                    // "Tugadi" holati fayl nomi almashishidan bir oz oldin kelishi
                    // mumkin — yana bir marta sinxronlaymiz, keyin EOF.
                    syncFile(true);
                    if (file != null) {
                        long fileLength = file.length();
                        if (currentOffset >= fileLength) {
                            return C.RESULT_END_OF_INPUT;
                        }
                        availableLength = (int) Math.min((long) targetLength, fileLength - currentOffset);
                        break;
                    }
                    throw new EOFException();
                }
                if (SystemClock.elapsedRealtime() > progressDeadline) {
                    throw new IOException("story stream stalled at " + currentOffset);
                }
                CountDownLatch latch = new CountDownLatch(1);
                countDownLatch = latch;
                FileLoadOperation operation = FileLoader.getInstance(account).loadStreamFile(
                        this, document, null, parentObject, currentOffset, false, FileLoader.PRIORITY_HIGH
                );
                if (operation == null) {
                    throw new IOException("stream operation unavailable");
                }
                if (operation != loadOperation) {
                    try {
                        loadOperation.removeStreamListener(this);
                    } catch (Exception ignore) {
                    }
                    loadOperation = operation;
                }
                try {
                    latch.await(STALL_WAIT_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
            }
            if (availableLength <= 0 || file == null) {
                return 0;
            }
            bytesRead = file.read(buffer, offset, availableLength);
            if (bytesRead > 0) {
                currentOffset += bytesRead;
                if (bytesRemaining > 0) {
                    bytesRemaining -= bytesRead;
                }
                if (transferListener != null && dataSpec != null) {
                    transferListener.onBytesTransferred(this, dataSpec, false, bytesRead);
                }
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
        return bytesRead;
    }

    /**
     * Joriy (temp yoki yakuniy) faylni ochadi/o'zgartirib qayta ochadi.
     * Yuklash tugagan bo'lsa yakuniy fayldan o'qiymiz — Telegram temp faylni
     * rename qilganda eski deskriptor o'lik bo'lib qoladi.
     */
    private void syncFile(boolean finished) {
        if (loadOperation == null) {
            return;
        }
        File next = finished ? loadOperation.getCacheFileFinal() : loadOperation.getCurrentFileFast();
        if (next == null || !next.isFile()) {
            return;
        }
        if (file != null && next.equals(currentFile)) {
            try {
                file.seek(currentOffset);
            } catch (Exception ignore) {
            }
            return;
        }
        if (file != null) {
            try {
                file.close();
            } catch (Exception ignore) {
            }
            file = null;
        }
        currentFile = next;
        try {
            file = new RandomAccessFile(next, "r");
            file.seek(currentOffset);
            if (finished) {
                bytesRemaining = Math.max(0L, next.length() - currentOffset);
            }
        } catch (Exception e) {
            file = null;
        }
    }

    @Override
    public Uri getUri() {
        return uri;
    }

    @Override
    public void close() {
        if (loadOperation != null) {
            loadOperation.removeStreamListener(this);
            loadOperation = null;
        }
        if (file != null) {
            try {
                file.close();
            } catch (Exception ignore) {
            }
            file = null;
        }
        uri = null;
        opened = false;
        if (countDownLatch != null) {
            countDownLatch.countDown();
            countDownLatch = null;
        }
        if (transferListener != null && dataSpec != null) {
            transferListener.onTransferEnd(this, dataSpec, false);
        }
        dataSpec = null;
    }

    @Override
    public void newDataAvailable() {
        CountDownLatch latch = countDownLatch;
        if (latch != null) {
            latch.countDown();
            countDownLatch = null;
        }
    }
}
