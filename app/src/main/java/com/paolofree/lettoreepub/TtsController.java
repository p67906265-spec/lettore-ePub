package com.paolofree.lettoreepub;

import android.content.Context;
import android.content.Intent;

/** Piccolo controller UI: la coda, i capitoli e il timer restano nel TtsService. */
public final class TtsController {
    private final Context context;
    public TtsController(Context context){this.context=context;}
    public void start(EpubBook book,int chapter,int offset,ReaderSettings settings){
        String language=languageKey(book.language);
        Intent i=new Intent(context,TtsService.class).setAction(TtsService.START)
                .putExtra("title",book.title).putExtra("path",book.file.getAbsolutePath())
                .putExtra("chapter",chapter).putExtra("from",offset).putExtra("language",book.language)
                .putExtra("rate",settings.prefs.getFloat("rate",1f)).putExtra("voice",settings.prefs.getString("voice_"+language,null))
                .putExtra("timer",settings.sleepTimer);
        context.startForegroundService(i);
    }
    public void action(String action){context.startService(new Intent(context,TtsService.class).setAction(action));}
    public void stop(String path){Intent i=new Intent(context,TtsService.class).setAction(TtsService.STOP);if(path!=null&&!path.isEmpty())i.putExtra("path",path);context.startService(i);}
    public static String languageKey(String value){if(value==null||value.trim().isEmpty())return "it";String key=value.trim().toLowerCase(java.util.Locale.ROOT).replace('_','-');int dash=key.indexOf('-');return (dash>0?key.substring(0,dash):key).replaceAll("[^a-z]","");}
}
