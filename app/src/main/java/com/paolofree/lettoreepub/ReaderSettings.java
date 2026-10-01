package com.paolofree.lettoreepub;

import android.content.Context;
import android.content.SharedPreferences;

/** Unico punto per preferenze grafiche, audio e posizione stabile nel testo. */
public final class ReaderSettings {
    public final SharedPreferences prefs;
    public int fontSize, margin, sleepTimer;
    public String theme, font;

    public ReaderSettings(Context context){
        prefs=context.getSharedPreferences("reader",Context.MODE_PRIVATE);
        fontSize=prefs.getInt("size",20);margin=prefs.getInt("margin",30);
        sleepTimer=prefs.getInt("timer",0);theme=prefs.getString("theme","Seppia");
        font=prefs.getString("font","Georgia");
    }
    public static String bookKey(String path){return Integer.toHexString(path.hashCode());}
    public String key(String path,String suffix){return bookKey(path)+"_"+suffix;}
    public int chapter(String path){return prefs.getInt(key(path,"chapter"),0);}
    public int offset(String path){return prefs.getInt(key(path,"offset"),prefs.getInt(key(path,"tts"),0));}
    public void savePosition(String path,int chapter,int offset,int chapterLength){
        float pct=chapterLength<=0?0f:Math.max(0f,Math.min(1f,offset/(float)chapterLength));
        prefs.edit().putInt(key(path,"chapter"),chapter).putInt(key(path,"offset"),offset)
                .putInt(key(path,"chapter_length"),chapterLength).putFloat(key(path,"chapter_percent"),pct).putLong(key(path,"last_read"),System.currentTimeMillis()).apply();
    }
    public void saveLook(){prefs.edit().putInt("size",fontSize).putInt("margin",margin).putString("theme",theme).putString("font",font).apply();}
}
