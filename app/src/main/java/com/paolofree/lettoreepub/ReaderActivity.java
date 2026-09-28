package com.paolofree.lettoreepub;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.util.Base64;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class ReaderActivity extends Activity {
    private EpubBook book; private File extracted; private WebView web;
    private TextView titleView,pageView; private android.content.SharedPreferences prefs;
    private int chapter,page,pageCount=1,fontSize=20,margin=30,ttsPosition,sleepTimer;
    private String theme="Seppia",font="Georgia"; private boolean turnLocked,speaking,paused;
    private float touchStartX,touchStartY;

    private final BroadcastReceiver voiceReceiver=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent i){
            if(book==null||i.getIntExtra("chapter",-1)!=chapter)return;
            String state=i.getStringExtra("state");ttsPosition=Math.max(0,i.getIntExtra("position",ttsPosition));
            speaking="playing".equals(state);paused="paused".equals(state);savePosition();
            if("chapter_done".equals(state)&&chapter<book.chapters.size()-1){
                chapter++;page=0;ttsPosition=0;showChapter(false);web.postDelayed(()->speak(),500);
            }else if("playing".equals(state))followVoice(i.getStringExtra("sentence"));
            else if(state!=null&&state.startsWith("Voce"))Toast.makeText(ReaderActivity.this,state,Toast.LENGTH_LONG).show();
        }
    };

    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs=getSharedPreferences("reader",0);
        try{
            String path=getIntent().getStringExtra("path");if(path==null){finish();return;}
            book=new EpubBook(new File(path));
            extracted=new File(getCacheDir(),"epub_"+Integer.toHexString((path+book.file.lastModified()).hashCode()));
            ProgressBar loading=new ProgressBar(this);setContentView(loading);
            new Thread(()->{try{book.extractTo(extracted);runOnUiThread(this::finishOpen);}catch(Exception e){runOnUiThread(()->new AlertDialog.Builder(this).setTitle("Impossibile estrarre il libro").setMessage(e.getMessage()).setPositiveButton("Chiudi",(d,w)->finish()).show());}}).start();
        }catch(Exception e){new AlertDialog.Builder(this).setTitle("Impossibile aprire il libro").setMessage(e.getMessage()).setPositiveButton("Chiudi",(d,w)->finish()).show();}
    }
    private void finishOpen(){
        chapter=prefs.getInt(key("chapter"),0);page=prefs.getInt(key("page"),0);ttsPosition=prefs.getInt(key("tts"),0);
        fontSize=prefs.getInt("size",20);margin=prefs.getInt("margin",30);theme=prefs.getString("theme","Seppia");
        font=prefs.getString("font","Georgia");sleepTimer=prefs.getInt("timer",0);
        buildInterface();showChapter(true);IntentFilter filter=new IntentFilter(TtsService.PROGRESS);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(voiceReceiver,filter,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(voiceReceiver,filter);
    }
    private String key(String suffix){return Integer.toHexString(book.file.getAbsolutePath().hashCode())+"_"+suffix;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private GradientDrawable panel(int color,int radius){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));return g;}
    private TextView button(String symbol,int accent){TextView v=new TextView(this);v.setText(symbol);v.setTextColor(Color.WHITE);v.setTextSize(22);v.setGravity(Gravity.CENTER);GradientDrawable g=panel(Color.rgb(13,38,57),28);g.setStroke(dp(2),accent);v.setBackground(g);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(58),1);lp.setMargins(dp(5),dp(6),dp(5),dp(6));v.setLayoutParams(lp);return v;}

    private void buildInterface(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.rgb(5,17,27));
        root.setOnApplyWindowInsetsListener((v,i)->{v.setPadding(0,i.getSystemWindowInsetTop(),0,i.getSystemWindowInsetBottom());return i;});
        LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);top.setPadding(dp(8),dp(5),dp(8),dp(5));top.setBackground(panel(Color.rgb(8,29,44),0));
        TextView back=button("‹",Color.rgb(72,199,232));back.setLayoutParams(new LinearLayout.LayoutParams(dp(54),dp(54)));back.setOnClickListener(v->finish());top.addView(back);
        LinearLayout heading=new LinearLayout(this);heading.setOrientation(LinearLayout.VERTICAL);heading.setPadding(dp(12),0,dp(8),0);
        titleView=new TextView(this);titleView.setTextColor(Color.WHITE);titleView.setTextSize(17);titleView.setSingleLine(true);titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView sub=new TextView(this);sub.setText("LETTORE EPUB • PAOLO FREE");sub.setTextColor(Color.rgb(72,199,232));sub.setTextSize(10);sub.setLetterSpacing(.12f);
        heading.addView(titleView);heading.addView(sub);top.addView(heading,new LinearLayout.LayoutParams(0,dp(60),1));
        TextView menu=button("☰",Color.rgb(255,187,51));menu.setLayoutParams(new LinearLayout.LayoutParams(dp(54),dp(54)));menu.setOnClickListener(v->settings());top.addView(menu);root.addView(top);

        FrameLayout frame=new FrameLayout(this);frame.setPadding(dp(7),dp(7),dp(7),dp(5));web=new WebView(this);web.setBackgroundColor(Color.TRANSPARENT);
        WebSettings s=web.getSettings();s.setJavaScriptEnabled(true);s.setAllowFileAccess(true);s.setAllowFileAccessFromFileURLs(false);s.setAllowUniversalAccessFromFileURLs(false);s.setBuiltInZoomControls(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setWebViewClient(new WebViewClient(){
            @Override public void onPageFinished(WebView v,String url){preparePages();}
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return openBookLink(r.getUrl());}
            @Override public boolean shouldOverrideUrlLoading(WebView v,String url){return openBookLink(Uri.parse(url));}
        });
        web.setOnTouchListener((v,e)->{
            if(e.getAction()==MotionEvent.ACTION_DOWN){touchStartX=e.getX();touchStartY=e.getY();return true;}
            if(e.getAction()==MotionEvent.ACTION_UP){float dx=e.getX()-touchStartX,dy=e.getY()-touchStartY;
                if(Math.abs(dx)>dp(55)&&Math.abs(dx)>Math.abs(dy)){if(dx<0)nextPage();else previousPage();}
                else if(Math.abs(dx)<dp(18)&&Math.abs(dy)<dp(18)){if(e.getX()>web.getWidth()*.68f)nextPage();else if(e.getX()<web.getWidth()*.32f)previousPage();else activateLinkAt(e.getX(),e.getY());}
                return true;}return true;
        });
        GradientDrawable paper=panel(Color.rgb(252,248,238),18);paper.setStroke(dp(1),Color.rgb(39,94,122));web.setBackground(paper);web.setClipToOutline(true);frame.addView(web,new FrameLayout.LayoutParams(-1,-1));root.addView(frame,new LinearLayout.LayoutParams(-1,0,1));
        pageView=new TextView(this);pageView.setTextColor(Color.rgb(164,191,205));pageView.setTextSize(12);pageView.setGravity(Gravity.CENTER);pageView.setOnClickListener(v->showProgress());root.addView(pageView,new LinearLayout.LayoutParams(-1,dp(28)));
        LinearLayout dock=new LinearLayout(this);dock.setGravity(Gravity.CENTER);dock.setPadding(dp(48),0,dp(48),dp(5));dock.setBackground(panel(Color.rgb(8,29,44),22));
        TextView read=button("🔊",Color.rgb(255,187,51));read.setOnClickListener(v->speak());TextView pause=button("Ⅱ",Color.rgb(72,199,232));pause.setOnClickListener(v->pauseResume());TextView stop=button("■",Color.rgb(235,92,92));stop.setOnClickListener(v->stopSpeak());
        dock.addView(read);dock.addView(pause);dock.addView(stop);root.addView(dock);setContentView(root);
    }
    private void showChapter(boolean restore){
        chapter=Math.max(0,Math.min(chapter,book.chapters.size()-1));if(!restore)page=0;pageCount=1;
        try{File f=book.chapterFile(extracted,chapter);String html=book.html(chapter,theme,fontSize,margin).replace("</head>","<style>body{--reader-font:"+font+"}</style></head>");web.loadDataWithBaseURL(f.getParentFile().toURI().toString(),html,"text/html","UTF-8",null);titleView.setText(book.title);updateIndicator();savePosition();}
        catch(Exception e){Toast.makeText(this,"Errore pagina: "+e.getMessage(),Toast.LENGTH_LONG).show();}
    }
    private void preparePages(){
        String js="(function(){var h=innerHeight;document.documentElement.style.cssText+=';width:'+innerWidth+'px!important;height:'+h+'px!important;overflow:hidden!important';document.body.style.cssText+=';box-sizing:border-box!important;width:'+innerWidth+'px!important;max-width:none!important;height:auto!important;overflow:visible!important';window.readerPageOffsets=[0];var usable=Math.max(200,h-48),max=Math.max(0,document.body.scrollHeight-h+40),cur=0;while(cur<max){cur=Math.min(max,cur+usable);window.readerPageOffsets.push(Math.round(cur));}return window.readerPageOffsets.length;})()";
        web.evaluateJavascript(js,r->{try{pageCount=Math.max(1,(int)Math.ceil(Double.parseDouble(r.replace("\"",""))));}catch(Exception ignored){pageCount=1;}page=Math.min(page,pageCount-1);moveToPage(false,0);});
    }
    private void moveToPage(boolean animate,int direction){String scroll="window.scrollTo(0,(window.readerPageOffsets&&window.readerPageOffsets["+page+"])||0)";if(!animate||direction==0){web.evaluateJavascript(scroll,null);turnLocked=false;}else{float d=web.getWidth()*.22f;web.animate().translationX(-direction*d).alpha(.2f).setDuration(120).withEndAction(()->{web.evaluateJavascript(scroll,null);web.setTranslationX(direction*d);web.animate().translationX(0).alpha(1).setDuration(170).withEndAction(()->turnLocked=false).start();}).start();}updateIndicator();savePosition();}
    private boolean lock(){if(turnLocked)return false;turnLocked=true;return true;}
    private void previousPage(){if(!lock())return;if(page>0){page--;moveToPage(true,-1);}else if(chapter>0){chapter--;page=9999;ttsPosition=0;showChapter(true);}else turnLocked=false;}
    private void nextPage(){if(!lock())return;if(page<pageCount-1){page++;moveToPage(true,1);}else if(chapter<book.chapters.size()-1){chapter++;page=0;ttsPosition=0;showChapter(false);}else{turnLocked=false;Toast.makeText(this,"Fine del libro",Toast.LENGTH_SHORT).show();}}
    private void activateLinkAt(float x,float y){web.evaluateJavascript("(function(){var e=document.elementFromPoint("+x+"/devicePixelRatio,"+y+"/devicePixelRatio),a=e&&e.closest?e.closest('a'):null;if(a){a.click();return true}return false})()",null);}
    private boolean openBookLink(Uri uri){int c=uri==null?-1:book.findChapter(uri.getPath());if(c>=0){stopSpeak();chapter=c;page=0;ttsPosition=0;showChapter(false);return true;}return false;}
    private void updateIndicator(){if(pageView!=null)pageView.setText(book.labels.get(chapter)+"   •   PAGINA "+(page+1)+" / "+pageCount);}
    private void savePosition(){prefs.edit().putInt(key("chapter"),chapter).putInt(key("page"),page).putInt(key("tts"),ttsPosition).apply();}

    private void speak(){
        try{String text=book.plain(chapter);int from=Math.max(ttsPosition,(int)((page/(double)Math.max(1,pageCount))*text.length()));Intent i=new Intent(this,TtsService.class).setAction(TtsService.START).putExtra("title",book.title).putExtra("path",book.file.getAbsolutePath()).putExtra("chapter",chapter).putExtra("text",text).putExtra("from",from).putExtra("rate",prefs.getFloat("rate",1f)).putExtra("voice",prefs.getString("voice",null)).putExtra("timer",sleepTimer);startForegroundService(i);}
        catch(Exception e){Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();}
    }
    private void pauseResume(){startService(new Intent(this,TtsService.class).setAction(paused?TtsService.RESUME:TtsService.PAUSE));}
    private void stopSpeak(){speaking=false;paused=false;stopService(new Intent(this,TtsService.class));}
    private void followVoice(String sentence){
        if(sentence==null||sentence.isEmpty())return;try{String text=book.plain(chapter);page=Math.min(pageCount-1,(int)((ttsPosition/(double)Math.max(1,text.length()))*pageCount));moveToPage(false,0);}catch(Exception ignored){}
        String b64=Base64.encodeToString(sentence.getBytes(StandardCharsets.UTF_8),Base64.NO_WRAP);
        String js="(function(){document.querySelectorAll('.tts-current').forEach(function(e){e.classList.remove('tts-current')});var q=new TextDecoder().decode(Uint8Array.from(atob('"+b64+"'),function(c){return c.charCodeAt(0)})).trim().substring(0,80);if(!q)return;var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT),n;while(n=w.nextNode()){if(n.nodeValue.indexOf(q)>=0){n.parentElement.classList.add('tts-current');break}}})()";web.evaluateJavascript(js,null);
    }

    private void settings(){
        String[] options={"Indice reale","Cerca nel capitolo","Aggiungi/rimuovi segnalibro","Segnalibri","Barra avanzamento","Testo più grande","Testo più piccolo","Margini stretti/medi/larghi","Scegli carattere","Tema chiaro","Tema seppia","Tema scuro","Velocità della voce","Scegli voce italiana","Timer spegnimento"};
        new AlertDialog.Builder(this).setTitle("Personalizza lettura").setItems(options,(d,w)->{
            if(w==0)showIndex();else if(w==1)search();else if(w==2)toggleBookmark();else if(w==3)showBookmarks();else if(w==4)showProgress();
            else if(w==5){fontSize=Math.min(36,fontSize+2);saveLook();}else if(w==6){fontSize=Math.max(14,fontSize-2);saveLook();}
            else if(w==7){margin=margin==18?30:margin==30?46:18;saveLook();}
            else if(w==8)chooseFont();else if(w>=9&&w<=11){theme=w==9?"Chiaro":w==10?"Seppia":"Scuro";saveLook();}
            else if(w==12)chooseRate();else if(w==13)chooseVoice();else chooseTimer();
        }).show();
    }
    private void saveLook(){double pct=page/(double)Math.max(1,pageCount-1);prefs.edit().putInt("size",fontSize).putInt("margin",margin).putString("theme",theme).putString("font",font).apply();showChapter(false);web.postDelayed(()->{page=Math.min(pageCount-1,(int)Math.round(pct*Math.max(0,pageCount-1)));moveToPage(false,0);},350);}
    private void showIndex(){String[] a=book.labels.toArray(new String[0]);new AlertDialog.Builder(this).setTitle("Indice del libro").setItems(a,(d,w)->{stopSpeak();chapter=w;page=0;ttsPosition=0;showChapter(false);}).show();}
    private void search(){EditText e=new EditText(this);e.setHint("Parola o frase");new AlertDialog.Builder(this).setTitle("Cerca nel capitolo").setView(e).setPositiveButton("Cerca",(d,w)->web.findAllAsync(e.getText().toString())).setNegativeButton("Annulla",null).show();}
    private String bookmarksKey(){return key("bookmarks");}
    private void toggleBookmark(){String mark=chapter+":"+page;Set<String>s=new HashSet<>(prefs.getStringSet(bookmarksKey(),Collections.emptySet()));if(s.remove(mark))Toast.makeText(this,"Segnalibro rimosso",Toast.LENGTH_SHORT).show();else{s.add(mark);Toast.makeText(this,"Segnalibro aggiunto",Toast.LENGTH_SHORT).show();}prefs.edit().putStringSet(bookmarksKey(),s).apply();}
    private void showBookmarks(){ArrayList<String> raw=new ArrayList<>(prefs.getStringSet(bookmarksKey(),Collections.emptySet()));raw.sort(Comparator.comparingInt(x->Integer.parseInt(x.split(":")[0])*10000+Integer.parseInt(x.split(":")[1])));String[] names=new String[raw.size()];for(int i=0;i<raw.size();i++){String[]p=raw.get(i).split(":");int c=Integer.parseInt(p[0]);names[i]=book.labels.get(c)+" • pagina "+(Integer.parseInt(p[1])+1);}new AlertDialog.Builder(this).setTitle("Segnalibri").setItems(names,(d,w)->{String[]p=raw.get(w).split(":");chapter=Integer.parseInt(p[0]);page=Integer.parseInt(p[1]);showChapter(true);}).show();}
    private void showProgress(){SeekBar bar=new SeekBar(this);bar.setMax(Math.max(0,book.chapters.size()*100-1));bar.setProgress(chapter*100+(int)(100.0*page/Math.max(1,pageCount)));new AlertDialog.Builder(this).setTitle("Avanzamento nel libro").setView(bar).setPositiveButton("Vai",(d,w)->{chapter=Math.min(book.chapters.size()-1,bar.getProgress()/100);page=0;ttsPosition=0;showChapter(false);}).setNegativeButton("Annulla",null).show();}
    private void chooseFont(){String[]a={"Georgia","sans-serif","serif","monospace"};new AlertDialog.Builder(this).setTitle("Carattere").setItems(a,(d,w)->{font=a[w];saveLook();}).show();}
    private void chooseRate(){String[]a={"0,75×","0,90×","1×","1,15×","1,30×"};float[]v={.75f,.9f,1f,1.15f,1.3f};new AlertDialog.Builder(this).setTitle("Velocità voce").setItems(a,(d,w)->prefs.edit().putFloat("rate",v[w]).apply()).show();}
    private void chooseVoice(){final android.speech.tts.TextToSpeech[] engine=new android.speech.tts.TextToSpeech[1];engine[0]=new android.speech.tts.TextToSpeech(this,status->{if(status!=android.speech.tts.TextToSpeech.SUCCESS){Toast.makeText(this,"Sintesi vocale non disponibile",Toast.LENGTH_LONG).show();return;}ArrayList<android.speech.tts.Voice> voices=new ArrayList<>();for(android.speech.tts.Voice v:engine[0].getVoices())if(v.getLocale()!=null&&"it".equals(v.getLocale().getLanguage()))voices.add(v);String[] names=new String[voices.size()];for(int i=0;i<voices.size();i++)names[i]=voices.get(i).getName();new AlertDialog.Builder(this).setTitle("Voce italiana").setItems(names,(d,w)->{prefs.edit().putString("voice",voices.get(w).getName()).apply();engine[0].shutdown();}).setOnCancelListener(d->engine[0].shutdown()).show();});}
    private void chooseTimer(){String[]a={"Disattivato","15 minuti","30 minuti","45 minuti","60 minuti"};int[]v={0,15,30,45,60};new AlertDialog.Builder(this).setTitle("Timer spegnimento").setItems(a,(d,w)->{sleepTimer=v[w];prefs.edit().putInt("timer",sleepTimer).apply();}).show();}
    @Override protected void onDestroy(){savePosition();try{unregisterReceiver(voiceReceiver);}catch(Exception ignored){}try{if(book!=null)book.close();}catch(Exception ignored){}if(web!=null)web.destroy();super.onDestroy();}
}
