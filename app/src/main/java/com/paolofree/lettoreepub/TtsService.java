package com.paolofree.lettoreepub;

import android.app.*;
import android.content.*;
import android.media.session.MediaSession;
import android.os.*;
import android.speech.tts.*;
import java.text.BreakIterator;
import java.util.*;

/** Sintesi vocale persistente con notifica e pausa reale. */
public final class TtsService extends Service implements TextToSpeech.OnInitListener {
    public static final String START="tts.start", PAUSE="tts.pause", RESUME="tts.resume", STOP="tts.stop";
    public static final String PROGRESS="com.paolofree.lettoreepub.TTS_PROGRESS";
    private static final String CHANNEL="lettura_vocale";
    private TextToSpeech tts; private MediaSession session;
    private final ArrayList<String> parts=new ArrayList<>(); private final ArrayList<Integer> starts=new ArrayList<>();
    private int part=0, absolute=0, chapter=0, timerMinutes=0, partOffset=0, utteranceBase=0; private String title="Lettore ePub",source="",requestedVoice=null; private float requestedRate=1f; private boolean ready=false,playing=false,paused=false;
    private Handler handler=new Handler(Looper.getMainLooper()); private Runnable timerStop;

    @Override public void onCreate(){super.onCreate();createChannel();session=new MediaSession(this,"LettoreEPub");session.setActive(true);tts=new TextToSpeech(this,this);}
    @Override public int onStartCommand(Intent in,int flags,int id){
        if(in==null)return START_NOT_STICKY;String action=in.getAction();
        if(STOP.equals(action)){stopReading(true);return START_NOT_STICKY;}
        if(PAUSE.equals(action)){pause();return START_STICKY;}
        if(RESUME.equals(action)){resume();return START_STICKY;}
        if(START.equals(action)){
            title=in.getStringExtra("title");source=in.getStringExtra("path");chapter=in.getIntExtra("chapter",0);timerMinutes=in.getIntExtra("timer",0);
            requestedRate=in.getFloatExtra("rate",1f);requestedVoice=in.getStringExtra("voice");String text=in.getStringExtra("text");int from=Math.max(0,in.getIntExtra("from",0));
            if(ready)applyVoice();prepare(text==null?"":text,from);playing=true;paused=false;startForeground(41,notification());
            if(ready)speakCurrent();scheduleTimer();
        }
        return START_STICKY;
    }
    private void prepare(String text,int from){parts.clear();starts.clear();BreakIterator it=BreakIterator.getSentenceInstance(Locale.ITALIAN);it.setText(text);int a=it.first(),b;while((b=it.next())!=BreakIterator.DONE){String s=text.substring(a,b).trim();if(!s.isEmpty()&&b>from){starts.add(a);parts.add(s);}a=b;}part=0;partOffset=parts.isEmpty()?0:Math.max(0,from-starts.get(0));absolute=parts.isEmpty()?from:starts.get(0)+partOffset;}
    @Override public void onInit(int status){
        ready=status==TextToSpeech.SUCCESS;if(!ready){broadcast("Voce non disponibile",-1,"");stopReading(true);return;}
        int r=tts.setLanguage(Locale.ITALIAN);if(r==TextToSpeech.LANG_MISSING_DATA||r==TextToSpeech.LANG_NOT_SUPPORTED){broadcast("Voce italiana non installata",-1,"");stopReading(true);return;}applyVoice();
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener(){
            @Override public void onStart(String id){broadcast("playing",absolute,current());}
            @Override public void onDone(String id){handler.post(()->{if(playing&&!paused){part++;partOffset=0;speakCurrent();}});}
            @Override public void onError(String id){handler.post(()->{part++;partOffset=0;speakCurrent();});}
            @Override public void onRangeStart(String id,int start,int end,int frame){partOffset=utteranceBase+Math.max(0,start);absolute=(part<starts.size()?starts.get(part):0)+partOffset;broadcast("playing",absolute,current());}
        });
        if(playing)speakCurrent();
    }
    private void applyVoice(){
        if(tts==null||!ready)return;
        try{
            tts.setSpeechRate(requestedRate);
            if(requestedVoice==null||requestedVoice.isEmpty()){tts.setLanguage(Locale.ITALIAN);return;}
            Voice selected=null;
            for(Voice v:tts.getVoices())if(requestedVoice.equals(v.getName())&&v.getLocale()!=null&&"it".equals(v.getLocale().getLanguage())&&!v.isNetworkConnectionRequired()){selected=v;break;}
            if(selected==null||tts.setVoice(selected)==TextToSpeech.ERROR){requestedVoice=null;tts.setLanguage(Locale.ITALIAN);}
        }catch(Exception ignored){requestedVoice=null;try{tts.setLanguage(Locale.ITALIAN);}catch(Exception ignoredAgain){}}
    }
    private String current(){return part<parts.size()?parts.get(part):"";}
    private void speakCurrent(){if(!playing||paused)return;if(part>=parts.size()){broadcast("chapter_done",absolute,"");stopReading(false);return;}absolute=starts.get(part)+partOffset;utteranceBase=partOffset;String value=parts.get(part);if(partOffset>0&&partOffset<value.length())value=value.substring(partOffset);tts.speak(value,TextToSpeech.QUEUE_FLUSH,null,"sentence-"+chapter+"-"+part);updateNotification();}
    private void pause(){if(!playing)return;paused=true;playing=false;if(tts!=null)tts.stop();broadcast("paused",absolute,current());updateNotification();}
    private void resume(){if(!paused)return;paused=false;playing=true;speakCurrent();}
    private void stopReading(boolean remove){playing=false;paused=false;if(tts!=null)tts.stop();if(timerStop!=null)handler.removeCallbacks(timerStop);broadcast("stopped",absolute,"");if(remove){stopForeground(true);stopSelf();}else updateNotification();}
    private void scheduleTimer(){if(timerStop!=null)handler.removeCallbacks(timerStop);if(timerMinutes>0){timerStop=()->stopReading(true);handler.postDelayed(timerStop,timerMinutes*60000L);}}
    private void broadcast(String state,int position,String sentence){Intent i=new Intent(PROGRESS).setPackage(getPackageName());i.putExtra("state",state).putExtra("position",position).putExtra("sentence",sentence).putExtra("chapter",chapter);sendBroadcast(i);}
    private PendingIntent action(String a,int code){return PendingIntent.getService(this,code,new Intent(this,TtsService.class).setAction(a),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
    private Notification notification(){Intent open=new Intent(this,ReaderActivity.class).putExtra("path",source).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);PendingIntent content=PendingIntent.getActivity(this,9,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);Notification.Builder b=new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_book).setContentTitle(title==null?"Lettore ePub":title).setContentText(paused?"Lettura in pausa":"Lettura vocale in corso").setContentIntent(content).setOngoing(playing).addAction(new Notification.Action.Builder(0,paused?"Riprendi":"Pausa",action(paused?RESUME:PAUSE,1)).build()).addAction(new Notification.Action.Builder(0,"Stop",action(STOP,2)).build()).setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0,1));return b.build();}
    private void updateNotification(){if(Build.VERSION.SDK_INT>=26)getSystemService(NotificationManager.class).notify(41,notification());}
    private void createChannel(){if(Build.VERSION.SDK_INT>=26)getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL,"Lettura vocale",NotificationManager.IMPORTANCE_LOW));}
    @Override public IBinder onBind(Intent i){return null;}
    @Override public void onDestroy(){if(tts!=null){tts.stop();tts.shutdown();}if(session!=null)session.release();super.onDestroy();}
}
