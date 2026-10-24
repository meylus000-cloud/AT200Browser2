package ua.at200.browser;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Налаштування, закладки та історія (SharedPreferences + JSON). */
public class Store {
    public static class Entry {
        public final String title;
        public final String url;

        public Entry(String title, String url) {
            this.title = title == null ? "" : title;
            this.url = url == null ? "" : url;
        }
    }

    private static final int MAX_HISTORY = 300;

    private final SharedPreferences sp;
    private List<Entry> bookmarks;
    private List<Entry> history;

    public Store(Context c) {
        sp = c.getSharedPreferences("browser", Context.MODE_PRIVATE);
    }

    // ---------- налаштування ----------

    public boolean js() {
        return sp.getBoolean("js", true);
    }

    public void setJs(boolean v) {
        sp.edit().putBoolean("js", v).commit();
    }

    public boolean images() {
        return sp.getBoolean("images", true);
    }

    public void setImages(boolean v) {
        sp.edit().putBoolean("images", v).commit();
    }

    public String home() {
        return sp.getString("home", "about:home");
    }

    public void setHome(String v) {
        sp.edit().putString("home", v).commit();
    }

    public int engine() {
        return sp.getInt("engine", 0);
    }

    public void setEngine(int v) {
        sp.edit().putInt("engine", v).commit();
    }

    public int textZoom() {
        return sp.getInt("zoom", 100);
    }

    public void setTextZoom(int v) {
        sp.edit().putInt("zoom", v).commit();
    }

    public String getString(String k, String def) {
        return sp.getString(k, def);
    }

    public void putString(String k, String v) {
        sp.edit().putString(k, v).commit();
    }

    public int getInt(String k, int def) {
        return sp.getInt(k, def);
    }

    public void putInt(String k, int v) {
        sp.edit().putInt(k, v).commit();
    }

    // ---------- закладки ----------

    public List<Entry> bookmarks() {
        if (bookmarks == null) bookmarks = read("bookmarks");
        return bookmarks;
    }

    public boolean isBookmarked(String url) {
        if (url == null) return false;
        for (Entry e : bookmarks()) {
            if (e.url.equals(url)) return true;
        }
        return false;
    }

    public void addBookmark(String title, String url) {
        if (isBookmarked(url)) return;
        bookmarks().add(0, new Entry(title.length() == 0 ? url : title, url));
        write("bookmarks", bookmarks());
    }

    public void removeBookmark(String url) {
        List<Entry> l = bookmarks();
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).url.equals(url)) {
                l.remove(i);
                break;
            }
        }
        write("bookmarks", l);
    }

    // ---------- історія ----------

    public List<Entry> history() {
        if (history == null) history = read("history");
        return history;
    }

    public void addHistory(String title, String url) {
        List<Entry> l = history();
        if (!l.isEmpty() && l.get(0).url.equals(url)) {
            l.remove(0);
        }
        l.add(0, new Entry(title.length() == 0 ? url : title, url));
        while (l.size() > MAX_HISTORY) l.remove(l.size() - 1);
        write("history", l);
    }

    public void removeHistory(String url) {
        List<Entry> l = history();
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).url.equals(url)) {
                l.remove(i);
                break;
            }
        }
        write("history", l);
    }

    public void clearHistory() {
        history().clear();
        write("history", history());
    }

    // ---------- JSON ----------

    private List<Entry> read(String key) {
        List<Entry> out = new ArrayList<Entry>();
        String s = sp.getString(key, null);
        if (s == null) return out;
        try {
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Entry(o.optString("t", ""), o.optString("u", "")));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private void write(String key, List<Entry> list) {
        try {
            JSONArray a = new JSONArray();
            for (Entry e : list) {
                JSONObject o = new JSONObject();
                o.put("t", e.title);
                o.put("u", e.url);
                a.put(o);
            }
            sp.edit().putString(key, a.toString()).commit();
        } catch (Exception ignored) {
        }
    }
}
