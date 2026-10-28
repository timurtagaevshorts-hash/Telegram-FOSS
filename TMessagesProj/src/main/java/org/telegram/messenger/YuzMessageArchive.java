package org.telegram.messenger;

import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Base64;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.SQLite.SQLitePreparedStatement;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

/**
 * YuzGram #227: o'chirilgan va tahrirlangan xabarlarning asl nusxasini saqlaydi.
 * - O'chirilgan xabar (butunlay) serializatsiya qilinib, chatda xira "зharf" sifatida qaytariladi.
 * - Tahrirlashda xabarning eng birinchi matni saqlanadi va iqtibos sifatida ko'rsatiladi.
 * Holat alohida SharedPreferences'da (yuz_originals) va alohida SQLite bazasida saqlanadi.
 */
public class YuzMessageArchive {

    public static final int REASON_EDIT = 0;
    public static final int REASON_DELETE = 1;

    private static final int MAX_PER_DIALOG = 200;
    private static final String PREFS_FILE = "yuz_originals";
    private static final String KEY_ENABLED = "enabled";

    private static final YuzMessageArchive[] Instance = new YuzMessageArchive[UserConfig.MAX_ACCOUNT_COUNT];

    public static YuzMessageArchive getInstance(int account) {
        YuzMessageArchive local = Instance[account];
        if (local == null) {
            synchronized (YuzMessageArchive.class) {
                local = Instance[account];
                if (local == null) {
                    local = new YuzMessageArchive(account);
                    Instance[account] = local;
                }
            }
        }
        return local;
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS_FILE, 0);
    }

    public static boolean isEnabled() {
        return prefs().getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(boolean value) {
        prefs().edit().putBoolean(KEY_ENABLED, value).apply();
    }

    public static class Ghost {
        public int id;
        public int date;
        public String quote;
        public byte[] data;
    }

    private final int currentAccount;
    private final DispatchQueue queue;
    private final HashMap<Long, HashMap<Integer, String>> edits = new HashMap<>();
    private final HashMap<Long, ArrayList<Ghost>> ghosts = new HashMap<>();
    private final HashMap<Long, HashMap<Integer, Ghost>> ghostIndex = new HashMap<>();
    private final ArrayList<Runnable> loadCallbacks = new ArrayList<>();
    private volatile boolean loaded;
    private volatile boolean loading;
    private SQLiteDatabase database;
    private boolean databaseCreateFailed;

    private YuzMessageArchive(int account) {
        currentAccount = account;
        queue = new DispatchQueue("yuzOriginalsQueue" + account);
    }

    public boolean isLoaded() {
        return loaded;
    }

    public void ensureLoaded(Runnable callback) {
        if (loaded) {
            if (callback != null) {
                callback.run();
            }
            return;
        }
        synchronized (loadCallbacks) {
            if (callback != null) {
                loadCallbacks.add(callback);
            }
            if (loading) {
                return;
            }
            loading = true;
        }
        queue.postRunnable(this::loadInternal);
    }

    private void loadInternal() {
        SQLiteDatabase db = getDatabase();
        if (db != null) {
            SQLiteCursor cursor = null;
            try {
                cursor = db.queryFinalized("SELECT dialog_id, message_id, reason, date, quote, data FROM originals");
                while (cursor.next()) {
                    long dialogId = cursor.longValue(0);
                    int messageId = cursor.intValue(1);
                    int reason = cursor.intValue(2);
                    int date = cursor.intValue(3);
                    String quote = cursor.isNull(4) ? null : cursor.stringValue(4);
                    String data = cursor.isNull(5) ? null : cursor.stringValue(5);
                    if (reason == REASON_EDIT) {
                        if (!TextUtils.isEmpty(quote)) {
                            synchronized (edits) {
                                HashMap<Integer, String> map = edits.get(dialogId);
                                if (map == null) {
                                    map = new HashMap<>();
                                    edits.put(dialogId, map);
                                }
                                if (!map.containsKey(messageId)) {
                                    map.put(messageId, quote);
                                }
                            }
                        }
                    } else {
                        Ghost ghost = new Ghost();
                        ghost.id = messageId;
                        ghost.date = date;
                        ghost.quote = quote;
                        ghost.data = decodeBytes(data);
                        if (ghost.data != null) {
                            addGhostInMemory(dialogId, ghost);
                        }
                    }
                }
            } catch (Throwable t) {
                FileLog.e(t);
            } finally {
                if (cursor != null) {
                    cursor.dispose();
                }
            }
        }
        loaded = true;
        AndroidUtilities.runOnUIThread(() -> {
            ArrayList<Runnable> callbacks;
            synchronized (loadCallbacks) {
                callbacks = new ArrayList<>(loadCallbacks);
                loadCallbacks.clear();
            }
            for (int a = 0, N = callbacks.size(); a < N; a++) {
                try {
                    callbacks.get(a).run();
                } catch (Throwable t) {
                    FileLog.e(t);
                }
            }
        });
    }

    public void rememberEdit(long dialogId, MessageObject oldObject, MessageObject newObject) {
        if (!isEnabled() || dialogId == 0 || oldObject == null || newObject == null) {
            return;
        }
        if (oldObject.yuzGhost || oldObject.messageOwner == null || newObject.messageOwner == null) {
            return;
        }
        // Secret chat matni shifrlangan — asl nusxa o'rniga kodsiz matn chiqmasin.
        if (DialogObject.isEncryptedDialog(dialogId)) {
            return;
        }
        int messageId = oldObject.getId();
        if (messageId <= 0 || oldObject.getId() != newObject.getId()) {
            return;
        }
        String oldText = oldObject.messageOwner.message;
        if (TextUtils.isEmpty(oldText)) {
            return;
        }
        if (oldText.equals(newObject.messageOwner.message)) {
            return;
        }
        synchronized (edits) {
            HashMap<Integer, String> map = edits.get(dialogId);
            if (map == null) {
                map = new HashMap<>();
                edits.put(dialogId, map);
            }
            if (map.containsKey(messageId)) {
                return;
            }
            map.put(messageId, oldText);
        }
        writeRow(dialogId, messageId, REASON_EDIT, oldObject.messageOwner.date, oldText, null);
    }

    public void rememberDelete(long dialogId, MessageObject messageObject) {
        if (!isEnabled() || dialogId == 0 || messageObject == null || messageObject.messageOwner == null) {
            return;
        }
        if (messageObject.yuzGhost || messageObject.getId() <= 0 || messageObject.isOutOwner()) {
            return;
        }
        if (messageObject.messageOwner.action != null || messageObject.isDateObject || messageObject.type == MessageObject.TYPE_LOADING) {
            return;
        }
        byte[] data = serialize(messageObject.messageOwner);
        if (data == null) {
            return;
        }
        Ghost ghost = new Ghost();
        ghost.id = messageObject.getId();
        ghost.date = messageObject.messageOwner.date;
        ghost.data = data;
        synchronized (ghosts) {
            HashMap<Integer, Ghost> index = ghostIndex.get(dialogId);
            if (index != null && index.containsKey(ghost.id)) {
                return;
            }
        }
        addGhostInMemory(dialogId, ghost);
        pruneGhosts(dialogId);
        writeRow(dialogId, ghost.id, REASON_DELETE, ghost.date, null, Base64.encodeToString(data, Base64.NO_WRAP));
    }

    private void addGhostInMemory(long dialogId, Ghost ghost) {
        synchronized (ghosts) {
            HashMap<Integer, Ghost> index = ghostIndex.get(dialogId);
            if (index == null) {
                index = new HashMap<>();
                ghostIndex.put(dialogId, index);
            }
            if (index.containsKey(ghost.id)) {
                return;
            }
            index.put(ghost.id, ghost);
            ArrayList<Ghost> list = ghosts.get(dialogId);
            if (list == null) {
                list = new ArrayList<>();
                ghosts.put(dialogId, list);
            }
            list.add(ghost);
        }
    }

    private void pruneGhosts(long dialogId) {
        ArrayList<Integer> removed = null;
        synchronized (ghosts) {
            ArrayList<Ghost> list = ghosts.get(dialogId);
            HashMap<Integer, Ghost> index = ghostIndex.get(dialogId);
            if (list == null || index == null || list.size() <= MAX_PER_DIALOG) {
                return;
            }
            Collections.sort(list, (a, b) -> Integer.compare(b.date, a.date));
            while (list.size() > MAX_PER_DIALOG) {
                Ghost ghost = list.remove(list.size() - 1);
                index.remove(ghost.id);
                if (removed == null) {
                    removed = new ArrayList<>();
                }
                removed.add(ghost.id);
            }
        }
        if (removed != null) {
            for (int a = 0, N = removed.size(); a < N; a++) {
                deleteRow(dialogId, removed.get(a), REASON_DELETE);
            }
        }
    }

    public String getEditQuote(long dialogId, int messageId) {
        synchronized (edits) {
            HashMap<Integer, String> map = edits.get(dialogId);
            return map == null ? null : map.get(messageId);
        }
    }

    public boolean hasEdit(long dialogId, int messageId) {
        return getEditQuote(dialogId, messageId) != null;
    }

    public ArrayList<Ghost> getGhosts(long dialogId) {
        ArrayList<Ghost> result;
        synchronized (ghosts) {
            ArrayList<Ghost> list = ghosts.get(dialogId);
            if (list == null || list.isEmpty()) {
                return null;
            }
            result = new ArrayList<>(list);
        }
        Collections.sort(result, (a, b) -> Integer.compare(b.date, a.date));
        return result;
    }

    public boolean hasHistory(long dialogId) {
        synchronized (edits) {
            HashMap<Integer, String> map = edits.get(dialogId);
            if (map != null && !map.isEmpty()) {
                return true;
            }
        }
        synchronized (ghosts) {
            ArrayList<Ghost> list = ghosts.get(dialogId);
            return list != null && !list.isEmpty();
        }
    }

    public void forgetGhost(long dialogId, int messageId) {
        boolean removed = false;
        synchronized (ghosts) {
            HashMap<Integer, Ghost> index = ghostIndex.get(dialogId);
            if (index != null && index.remove(messageId) != null) {
                removed = true;
            }
            ArrayList<Ghost> list = ghosts.get(dialogId);
            if (list != null) {
                for (int a = list.size() - 1; a >= 0; a--) {
                    if (list.get(a).id == messageId) {
                        list.remove(a);
                        removed = true;
                    }
                }
            }
        }
        if (removed) {
            deleteRow(dialogId, messageId, REASON_DELETE);
        }
    }

    public void clearDialog(long dialogId) {
        boolean hasEdits;
        synchronized (edits) {
            hasEdits = edits.remove(dialogId) != null;
        }
        boolean hasGhosts;
        synchronized (ghosts) {
            hasGhosts = ghosts.remove(dialogId) != null;
            ghostIndex.remove(dialogId);
        }
        if (hasEdits || hasGhosts) {
            queue.postRunnable(() -> {
                SQLiteDatabase db = getDatabase();
                if (db == null) {
                    return;
                }
                try {
                    db.executeFast("DELETE FROM originals WHERE dialog_id = " + dialogId).stepThis().dispose();
                } catch (Throwable t) {
                    FileLog.e(t);
                }
            });
        }
    }

    private void writeRow(long dialogId, int messageId, int reason, int date, String quote, String data) {
        queue.postRunnable(() -> {
            SQLiteDatabase db = getDatabase();
            if (db == null) {
                return;
            }
            SQLitePreparedStatement state = null;
            try {
                state = db.executeFast("REPLACE INTO originals VALUES(?, ?, ?, ?, ?, ?)");
                state.bindLong(1, dialogId);
                state.bindInteger(2, messageId);
                state.bindInteger(3, reason);
                state.bindInteger(4, date);
                if (quote != null) {
                    state.bindString(5, quote);
                } else {
                    state.bindNull(5);
                }
                if (data != null) {
                    state.bindString(6, data);
                } else {
                    state.bindNull(6);
                }
                state.step();
            } catch (Throwable t) {
                FileLog.e(t);
            } finally {
                if (state != null) {
                    state.dispose();
                }
            }
        });
    }

    private void deleteRow(long dialogId, int messageId, int reason) {
        queue.postRunnable(() -> {
            SQLiteDatabase db = getDatabase();
            if (db == null) {
                return;
            }
            try {
                db.executeFast("DELETE FROM originals WHERE dialog_id = " + dialogId + " AND message_id = " + messageId + " AND reason = " + reason).stepThis().dispose();
            } catch (Throwable t) {
                FileLog.e(t);
            }
        });
    }

    private SQLiteDatabase getDatabase() {
        if (database != null) {
            return database;
        }
        if (databaseCreateFailed) {
            return null;
        }
        try {
            File dir = ApplicationLoader.getFilesDirFixed();
            if (currentAccount != 0) {
                dir = new File(dir, "account" + currentAccount + "/");
                dir.mkdirs();
            }
            database = new SQLiteDatabase(new File(dir, "yuz_originals.db").getPath());
            database.executeFast("PRAGMA journal_mode = WAL").stepThis().dispose();
            database.executeFast("CREATE TABLE IF NOT EXISTS originals(dialog_id INTEGER, message_id INTEGER, reason INTEGER, date INTEGER, quote TEXT, data TEXT, PRIMARY KEY(dialog_id, message_id, reason))").stepThis().dispose();
            database.executeFast("CREATE INDEX IF NOT EXISTS idx_originals_dialog ON originals(dialog_id)").stepThis().dispose();
        } catch (Throwable t) {
            FileLog.e(t);
            database = null;
            databaseCreateFailed = true;
        }
        return database;
    }

    public MessageObject restoreGhost(long dialogId, Ghost ghost) {
        TLRPC.Message message = deserialize(ghost.data);
        if (message == null || message.out) {
            return null;
        }
        message.dialog_id = dialogId;
        MessageObject messageObject;
        try {
            messageObject = new MessageObject(currentAccount, message, true, true);
        } catch (Throwable t) {
            FileLog.e(t);
            return null;
        }
        messageObject.yuzGhost = true;
        messageObject.deleted = false;
        messageObject.yuzQuoteText = ghost.quote;
        return messageObject;
    }

    private static byte[] serialize(TLRPC.Message message) {
        NativeByteBuffer buffer = null;
        try {
            buffer = new NativeByteBuffer(message.getObjectSize());
            message.serializeToStream(buffer);
            int len = buffer.position();
            if (len <= 0) {
                return null;
            }
            byte[] bytes = new byte[len];
            buffer.position(0);
            buffer.readBytes(bytes, false);
            return bytes;
        } catch (Throwable t) {
            FileLog.e(t);
            return null;
        } finally {
            if (buffer != null) {
                buffer.reuse();
            }
        }
    }

    private TLRPC.Message deserialize(byte[] bytes) {
        NativeByteBuffer buffer = null;
        try {
            buffer = new NativeByteBuffer(bytes.length);
            buffer.writeBytes(bytes);
            buffer.position(0);
            TLRPC.Message message = TLRPC.Message.TLdeserialize(buffer, buffer.readInt32(false), false);
            return message;
        } catch (Throwable t) {
            FileLog.e(t);
            return null;
        } finally {
            if (buffer != null) {
                buffer.reuse();
            }
        }
    }

    private static byte[] decodeBytes(String value) {
        if (TextUtils.isEmpty(value)) {
            return null;
        }
        try {
            return Base64.decode(value, Base64.NO_WRAP);
        } catch (Throwable t) {
            FileLog.e(t);
            return null;
        }
    }
}
