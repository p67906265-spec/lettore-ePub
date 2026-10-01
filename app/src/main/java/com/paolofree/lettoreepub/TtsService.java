package com.paolofree.lettoreepub;

import android.app.*;
import android.content.*;
import android.media.*;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.*;
import android.speech.tts.*;
import android.view.KeyEvent;
import java.text.BreakIterator;
import java.util.*;

/** Lettore vocale autonomo: continua tra capitoli anche senza Activity. */
public final class TtsService extends Service implements TextToSpeech.OnInitListener, AudioManager.OnAudioFocusChangeListener {
    public static final String START="tts.start",PAUSE="tts.pause",RESUME="tts.resume",STOP="tts.stop",PREVIOUS="tts.previous",NEXT="tts.next";
    public static final String PROGRESS="com.paolofree.lettoreepub.TTS_PROGRESS";
    private static final String CHANNEL="lettura_vocale";
    private final ArrayList<String> parts=new ArrayList<>();
    private final ArrayList<Integer> starts=new ArrayList<>();
    private TextToSpeech tts;private MediaSession session;private AudioManager audio;private AudioFocusRequest focusRequest;private EpubBook book;
    private int part,absolute,chapter,utteranceBase;private long timerDeadline,lastPrefSave;private String title="Lettore ePub",source="",language="it",requestedVoice;private float requestedRate=1f;
    private boolean ready,playing,paused,resumeAfterFocusLoss,noisyRegistered,foregroundStarted;
    private final Handler handler=new Handler(Looper.getMainLooper());private Runnable timerStop;
    private final BroadcastReceiver noisyReceiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){if(AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(i.getAction()))pause();}};

    @Override public void onCreate(){
        super.onCreate();createChannel();audio=(AudioManager)getSystemService(AUDIO_SERVICE);
        session=new MediaSession(this,"LettoreEPub");session.setCallback(new MediaSession.Callback(){
            @Override public void onPlay(){resume();}@Override public void onPause(){pause();}@Override public void onStop(){stopReading(true);}
            @Override public void onSkipToNext(){nextSentence();}@Override public void onSkipToPrevious(){previousSentence();}
            @Override public boolean onMediaButtonEvent(Intent mediaButtonIntent){KeyEvent e=mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);if(e!=null&&e.getAction()==KeyEvent.ACTION_DOWN){if(e.getKeyCode()==KeyEvent.KEYCODE_MEDIA_NEXT)nextSentence();else if(e.getKeyCode()==KeyEvent.KEYCODE_MEDIA_PREVIOUS)previousSentence();else if(e.getKeyCode()==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE||e.getKeyCode()==KeyEvent.KEYCODE_HEADSETHOOK){if(paused)resume();else pause();}return true;}return super.onMediaButtonEvent(mediaButtonIntent);}
        });session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS|MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);session.setActive(true);
        tts=new TextToSpeech(this,this);
    }
    @Override public int onStartCommand(Intent in,int flags,int id){
        if(in==null)return START_STICKY;String action=in.getAction();
        if(STOP.equals(action)){String target=in.getStringExtra("path");if(target==null||target.isEmpty()||Objects.equals(source,target))stopReading(true);else if(source.isEmpty())stopSelf();return START_NOT_STICKY;}if(PAUSE.equals(action)){pause();return START_STICKY;}if(RESUME.equals(action)){resume();return START_STICKY;}if(NEXT.equals(action)){nextSentence();return START_STICKY;}if(PREVIOUS.equals(action)){previousSentence();return START_STICKY;}
        if(START.equals(action)){
            String newSource=in.getStringExtra("path");boolean same=Objects.equals(source,newSource)&&book!=null;
            title=in.getStringExtra("title");source=newSource;chapter=in.getIntExtra("chapter",0);language=in.getStringExtra("language");if(language==null||language.isEmpty())language="it";
            getSharedPreferences("reader",0).edit().putString("tts_active_source",source).apply();
            requestedRate=in.getFloatExtra("rate",1f);requestedVoice=in.getStringExtra("voice");int timer=in.getIntExtra("timer",0);
            playing=true;paused=false;startForeground(41,notification());foregroundStarted=true;
            long saved=getSharedPreferences("reader",0).getLong("tts_timer_deadline",0L);long now=System.currentTimeMillis();
            if(timer<=0)timerDeadline=0L;else if(same&&saved>now)timerDeadline=saved;else timerDeadline=now+timer*60000L;
            getSharedPreferences("reader",0).edit().putLong("tts_timer_deadline",timerDeadline).apply();
            try{if(!same){if(book!=null)book.close();book=new EpubBook(new java.io.File(source));}loadChapter(in.getIntExtra("from",0));}catch(Exception e){broadcast("Errore lettura: "+e.getMessage(),-1,"");stopReading(true);return START_NOT_STICKY;}
            if(ready){applyVoice();if(requestFocus())speakQueue();}scheduleTimer();
        }
        return START_STICKY;
    }
    private void loadChapter(int from)throws Exception{chapter=Math.max(0,Math.min(chapter,book.chapters.size()-1));prepare(book.plain(chapter),from);}
    private void prepare(String text,int from){parts.clear();starts.clear();BreakIterator it=BreakIterator.getSentenceInstance(locale());it.setText(text);int a=it.first(),b;while((b=it.next())!=BreakIterator.DONE){addSplit(text.substring(a,b),a,from);a=b;}if(a<text.length())addSplit(text.substring(a),a,from);part=0;absolute=parts.isEmpty()?from:starts.get(0);utteranceBase=0;}
    private void addSplit(String sourcePart,int base,int from){int local=0;while(local<sourcePart.length()){int end=Math.min(sourcePart.length(),local+3500);if(end<sourcePart.length()){int cut=sourcePart.lastIndexOf(' ',end);if(cut>local+500)end=cut;}String value=sourcePart.substring(local,end).trim();int start=base+local;int leading=sourcePart.substring(local,end).indexOf(value);if(leading>0)start+=leading;if(!value.isEmpty()&&start+value.length()>from){int trim=Math.max(0,from-start);if(trim<value.length()){starts.add(start+trim);parts.add(value.substring(trim));}}local=end;while(local<sourcePart.length()&&Character.isWhitespace(sourcePart.charAt(local)))local++;}}
    @Override public void onInit(int status){ready=status==TextToSpeech.SUCCESS;if(!ready){broadcast("Voce non disponibile",-1,"");stopReading(true);return;}applyVoice();tts.setOnUtteranceProgressListener(new UtteranceProgressListener(){
        @Override public void onStart(String id){int p=parsePart(id);if(p>=0&&p<starts.size()&&p<parts.size()){part=p;absolute=starts.get(p);saveProgress(true);broadcast("playing",absolute,parts.get(p));}}
        @Override public void onDone(String id){handler.post(()->{int p=parsePart(id);if(p<0)return;part=p+1;if(part>=parts.size())advanceChapter();});}
        @Override public void onError(String id){handler.post(()->{int p=parsePart(id);if(p<0)return;part=Math.max(part,p+1);if(part>=parts.size())advanceChapter();});}
        @Override public void onRangeStart(String id,int start,int end,int frame){int p=parsePart(id);if(p>=0&&p<starts.size()){part=p;utteranceBase=Math.max(0,start);absolute=starts.get(p)+utteranceBase;saveProgress(false);broadcast("position",absolute,"");}}
    });if(playing&&requestFocus())speakQueue();}
    private int parsePart(String id){try{String prefix="c"+chapter+"-";if(id==null||!id.startsWith(prefix))return -1;return Integer.parseInt(id.substring(prefix.length()));}catch(Exception e){return -1;}}
    private Locale locale(){try{return Locale.forLanguageTag(language.replace('_','-'));}catch(Exception e){return Locale.ITALIAN;}}
    private void applyVoice(){if(tts==null||!ready)return;tts.setSpeechRate(requestedRate);Locale wanted=locale();try{if(requestedVoice!=null&&!requestedVoice.isEmpty())for(Voice v:tts.getVoices())if(requestedVoice.equals(v.getName())&&v.getLocale()!=null&&wanted.getLanguage().equals(v.getLocale().getLanguage())&&!v.isNetworkConnectionRequired()){tts.setVoice(v);return;}}catch(Exception ignored){}int result=tts.setLanguage(wanted);if(result==TextToSpeech.LANG_MISSING_DATA||result==TextToSpeech.LANG_NOT_SUPPORTED)tts.setLanguage(Locale.ITALIAN);}
    private void speakQueue(){if(!playing||paused||parts.isEmpty())return;tts.stop();for(int i=part;i<parts.size();i++)tts.speak(parts.get(i),i==part?TextToSpeech.QUEUE_FLUSH:TextToSpeech.QUEUE_ADD,null,"c"+chapter+"-"+i);updateSession();updateNotification();}
    private void advanceChapter(){if(!playing||paused)return;if(book!=null&&chapter<book.chapters.size()-1){chapter++;try{loadChapter(0);saveProgress(true);broadcast("chapter_changed",0,"");speakQueue();}catch(Exception e){stopReading(true);}}else{broadcast("book_done",absolute,"");stopReading(true);}}
    private void nextSentence(){if(parts.isEmpty())return;part=Math.min(parts.size()-1,part+1);absolute=starts.get(part);playing=true;paused=false;if(requestFocus())speakQueue();}
    private void previousSentence(){if(parts.isEmpty())return;part=Math.max(0,part-1);absolute=starts.get(part);playing=true;paused=false;if(requestFocus())speakQueue();}
    private void pause(){if(!playing&&!resumeAfterFocusLoss)return;paused=true;playing=false;if(tts!=null)tts.stop();broadcast("paused",absolute,current());updateSession();updateNotification();}
    private void resume(){if(!paused)return;paused=false;playing=true;if(requestFocus())speakQueue();}
    private String current(){return part>=0&&part<parts.size()?parts.get(part):"";}
    private boolean requestFocus(){AudioAttributes attrs=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();focusRequest=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attrs).setOnAudioFocusChangeListener(this).setWillPauseWhenDucked(true).build();int result=audio.requestAudioFocus(focusRequest);if(result==AudioManager.AUDIOFOCUS_REQUEST_GRANTED&&!noisyRegistered){registerReceiver(noisyReceiver,new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));noisyRegistered=true;}return result==AudioManager.AUDIOFOCUS_REQUEST_GRANTED;}
    @Override public void onAudioFocusChange(int change){if(change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT||change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK){resumeAfterFocusLoss=playing;pause();}else if(change==AudioManager.AUDIOFOCUS_GAIN&&resumeAfterFocusLoss){resumeAfterFocusLoss=false;resume();}else if(change==AudioManager.AUDIOFOCUS_LOSS){resumeAfterFocusLoss=false;pause();}}
    private void stopReading(boolean remove){playing=false;paused=false;if(tts!=null)tts.stop();if(timerStop!=null)handler.removeCallbacks(timerStop);if(focusRequest!=null&&audio!=null)audio.abandonAudioFocusRequest(focusRequest);getSharedPreferences("reader",0).edit().remove("tts_timer_deadline").remove("tts_active_source").apply();broadcast("stopped",absolute,"");if(session!=null)updateSession();if(remove){if(foregroundStarted){stopForeground(true);foregroundStarted=false;}stopSelf();}}
    private void scheduleTimer(){if(timerStop!=null)handler.removeCallbacks(timerStop);if(timerDeadline>0){long delay=timerDeadline-System.currentTimeMillis();if(delay<=0)stopReading(true);else{timerStop=()->stopReading(true);handler.postDelayed(timerStop,delay);}}}
    private void saveProgress(boolean force){long now=SystemClock.uptimeMillis();if(!force&&now-lastPrefSave<2000)return;lastPrefSave=now;if(source==null||source.isEmpty())return;String key=ReaderSettings.bookKey(source);int length=1;try{length=Math.max(1,book.plain(chapter).length());}catch(Exception ignored){}getSharedPreferences("reader",0).edit().putInt(key+"_chapter",chapter).putInt(key+"_offset",absolute).putInt(key+"_chapter_length",length).putFloat(key+"_chapter_percent",Math.max(0f,Math.min(1f,absolute/(float)length))).putLong(key+"_last_read",System.currentTimeMillis()).apply();}
    private void updateSession(){long actions=PlaybackState.ACTION_PLAY|PlaybackState.ACTION_PAUSE|PlaybackState.ACTION_STOP|PlaybackState.ACTION_SKIP_TO_NEXT|PlaybackState.ACTION_SKIP_TO_PREVIOUS;session.setPlaybackState(new PlaybackState.Builder().setActions(actions).setState(playing?PlaybackState.STATE_PLAYING:paused?PlaybackState.STATE_PAUSED:PlaybackState.STATE_STOPPED,absolute,1f).build());}
    private void broadcast(String state,int position,String sentence){Intent i=new Intent(PROGRESS).setPackage(getPackageName()).putExtra("state",state).putExtra("position",position).putExtra("sentence",sentence).putExtra("chapter",chapter).putExtra("path",source);sendBroadcast(i);}
    private PendingIntent action(String value,int code){return PendingIntent.getService(this,code,new Intent(this,TtsService.class).setAction(value),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
    private Notification notification(){Intent open=new Intent(this,ReaderActivity.class).putExtra("path",source).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);PendingIntent content=PendingIntent.getActivity(this,9,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);Notification.Builder b=new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_book).setContentTitle(title==null?"Lettore ePub":title).setContentText(paused?"Lettura in pausa":"Lettura vocale in corso").setContentIntent(content).setOngoing(playing).addAction(new Notification.Action.Builder(0,"Precedente",action(PREVIOUS,3)).build()).addAction(new Notification.Action.Builder(0,paused?"Riprendi":"Pausa",action(paused?RESUME:PAUSE,1)).build()).addAction(new Notification.Action.Builder(0,"Successiva",action(NEXT,4)).build()).addAction(new Notification.Action.Builder(0,"Stop",action(STOP,2)).build()).setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0,1,2));return b.build();}
    private void updateNotification(){if(Build.VERSION.SDK_INT>=26)getSystemService(NotificationManager.class).notify(41,notification());}
    private void createChannel(){if(Build.VERSION.SDK_INT>=26)getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL,"Lettura vocale",NotificationManager.IMPORTANCE_LOW));}
    @Override public IBinder onBind(Intent i){return null;}
    @Override public void onDestroy(){if(noisyRegistered)try{unregisterReceiver(noisyReceiver);}catch(Exception ignored){}if(tts!=null){tts.stop();tts.shutdown();}if(book!=null)try{book.close();}catch(Exception ignored){}if(session!=null)session.release();super.onDestroy();}
}
