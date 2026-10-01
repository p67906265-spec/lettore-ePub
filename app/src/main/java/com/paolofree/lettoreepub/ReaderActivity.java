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
    private EpubBook book; private File extracted; private WebView web;private Paginator paginator;private ReaderSettings settings;private TtsController ttsController;
    private TextView titleView,pageView,openMenuHeader; private View topBar,bottomDock; private LinearLayout openMenuCard; private android.content.SharedPreferences prefs;
    private int chapter,page,pageCount=1,fontSize=20,margin=30,ttsPosition,sleepTimer;private String chapterText="",pendingAnchor="";private long lastPositionSave;
    private String theme="Seppia",font="Georgia"; private boolean turnLocked,speaking,paused,chromeVisible=true; private double pendingPagePercent=-1d;
    private float touchStartX,touchStartY;

    private final BroadcastReceiver voiceReceiver=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent i){
            if(book==null)return;int serviceChapter=i.getIntExtra("chapter",chapter);if(serviceChapter<0||serviceChapter>=book.chapters.size())return;
            String state=i.getStringExtra("state");boolean changed=serviceChapter!=chapter;if(changed){chapter=serviceChapter;ttsPosition=Math.max(0,i.getIntExtra("position",0));showChapter(true);}else ttsPosition=Math.max(0,i.getIntExtra("position",ttsPosition));
            if("playing".equals(state)||"position".equals(state)||"chapter_changed".equals(state)){speaking=true;paused=false;}else if("paused".equals(state)){speaking=false;paused=true;}else if("stopped".equals(state)||"book_done".equals(state)){speaking=false;paused=false;}savePosition(false);
            if("playing".equals(state))followVoice(i.getStringExtra("sentence"));
            else if(state!=null&&state.startsWith("Voce"))Toast.makeText(ReaderActivity.this,state,Toast.LENGTH_LONG).show();
        }
    };

    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        settings=new ReaderSettings(this);prefs=settings.prefs;ttsController=new TtsController(this);
        try{
            String path=getIntent().getStringExtra("path");if(path==null){finish();return;}
            book=new EpubBook(new File(path));
            extracted=new File(getCacheDir(),"epub_"+Integer.toHexString((path+book.file.lastModified()).hashCode()));
            ProgressBar loading=new ProgressBar(this);setContentView(loading);
            new Thread(()->{try{book.extractTo(extracted);runOnUiThread(this::finishOpen);}catch(Exception e){runOnUiThread(()->new AlertDialog.Builder(this).setTitle("Impossibile estrarre il libro").setMessage(e.getMessage()).setPositiveButton("Chiudi",(d,w)->finish()).show());}}).start();
        }catch(Exception e){new AlertDialog.Builder(this).setTitle("Impossibile aprire il libro").setMessage(e.getMessage()).setPositiveButton("Chiudi",(d,w)->finish()).show();}
    }
    private void finishOpen(){
        chapter=settings.chapter(book.file.getAbsolutePath());ttsPosition=settings.offset(book.file.getAbsolutePath());
        fontSize=settings.fontSize;margin=settings.margin;theme=settings.theme;font=settings.font;sleepTimer=settings.sleepTimer;
        buildInterface();migrateBookmarks();showChapter(true);IntentFilter filter=new IntentFilter(TtsService.PROGRESS);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(voiceReceiver,filter,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(voiceReceiver,filter);
    }
    private String key(String suffix){return Integer.toHexString(book.file.getAbsolutePath().hashCode())+"_"+suffix;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private GradientDrawable panel(int color,int radius){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));return g;}
    private TextView button(String symbol,int accent){TextView v=new TextView(this);v.setText(symbol);v.setTextColor(Color.WHITE);v.setTextSize(22);v.setGravity(Gravity.CENTER);GradientDrawable g=panel(Color.rgb(13,38,57),28);g.setStroke(dp(2),accent);v.setBackground(g);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(58),1);lp.setMargins(dp(5),dp(6),dp(5),dp(6));v.setLayoutParams(lp);return v;}

    private void buildInterface(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.rgb(5,17,27));
        root.setOnApplyWindowInsetsListener((v,i)->{v.setPadding(0,i.getSystemWindowInsetTop(),0,i.getSystemWindowInsetBottom());return i;});
        LinearLayout top=new LinearLayout(this);topBar=top;top.setGravity(Gravity.CENTER_VERTICAL);top.setPadding(dp(8),dp(5),dp(8),dp(5));top.setBackground(panel(Color.rgb(8,29,44),0));
        TextView back=button("‹",Color.rgb(72,199,232));back.setLayoutParams(new LinearLayout.LayoutParams(dp(54),dp(54)));back.setOnClickListener(v->finish());top.addView(back);
        LinearLayout heading=new LinearLayout(this);heading.setOrientation(LinearLayout.VERTICAL);heading.setPadding(dp(12),0,dp(8),0);
        titleView=new TextView(this);titleView.setTextColor(Color.WHITE);titleView.setTextSize(17);titleView.setSingleLine(true);titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView sub=new TextView(this);sub.setText("LETTORE EPUB • PAOLO FREE");sub.setTextColor(Color.rgb(72,199,232));sub.setTextSize(10);sub.setLetterSpacing(.12f);
        heading.addView(titleView);heading.addView(sub);top.addView(heading,new LinearLayout.LayoutParams(0,dp(60),1));
        TextView menu=button("☰",Color.rgb(255,187,51));menu.setLayoutParams(new LinearLayout.LayoutParams(dp(54),dp(54)));menu.setOnClickListener(v->settings());top.addView(menu);root.addView(top);

        FrameLayout frame=new FrameLayout(this);frame.setPadding(dp(7),dp(7),dp(7),dp(5));web=new WebView(this);web.setBackgroundColor(Color.TRANSPARENT);
        WebSettings s=web.getSettings();s.setJavaScriptEnabled(true);s.setAllowFileAccess(true);s.setAllowFileAccessFromFileURLs(false);s.setAllowUniversalAccessFromFileURLs(false);s.setBuiltInZoomControls(false);s.setBlockNetworkLoads(true);
        paginator=new Paginator(web);paginator.setMargin(margin);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setWebViewClient(new WebViewClient(){
            @Override public void onPageFinished(WebView v,String url){preparePages();}
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return openLink(r.getUrl());}
            @Override public boolean shouldOverrideUrlLoading(WebView v,String url){return openLink(Uri.parse(url));}
        });
        web.setOnTouchListener((v,e)->{
            if(e.getAction()==MotionEvent.ACTION_DOWN){touchStartX=e.getX();touchStartY=e.getY();return true;}
            if(e.getAction()==MotionEvent.ACTION_UP){float dx=e.getX()-touchStartX,dy=e.getY()-touchStartY;
                if(Math.abs(dx)>dp(55)&&Math.abs(dx)>Math.abs(dy)){if(dx<0)nextPage();else previousPage();}
                else if(Math.abs(dx)<dp(18)&&Math.abs(dy)<dp(18)){if(e.getX()>web.getWidth()*.68f)nextPage();else if(e.getX()<web.getWidth()*.32f)previousPage();else activateLinkOrToggle(e.getX(),e.getY());}
                return true;}return true;
        });
        GradientDrawable paper=panel(Color.rgb(252,248,238),18);paper.setStroke(dp(1),Color.rgb(39,94,122));web.setBackground(paper);web.setClipToOutline(true);frame.addView(web,new FrameLayout.LayoutParams(-1,-1));root.addView(frame,new LinearLayout.LayoutParams(-1,0,1));
        pageView=new TextView(this);pageView.setTextColor(Color.rgb(164,191,205));pageView.setTextSize(12);pageView.setGravity(Gravity.CENTER);pageView.setOnClickListener(v->showProgress());root.addView(pageView,new LinearLayout.LayoutParams(-1,dp(28)));
        LinearLayout dock=new LinearLayout(this);bottomDock=dock;dock.setGravity(Gravity.CENTER);dock.setPadding(dp(48),0,dp(48),dp(5));dock.setBackground(panel(Color.rgb(8,29,44),22));
        TextView read=button("🔊",Color.rgb(255,187,51));read.setOnClickListener(v->speak());TextView pause=button("Ⅱ",Color.rgb(72,199,232));pause.setOnClickListener(v->pauseResume());TextView stop=button("■",Color.rgb(235,92,92));stop.setOnClickListener(v->stopSpeak());
        dock.addView(read);dock.addView(pause);dock.addView(stop);root.addView(dock);setContentView(root);
    }
    private void showChapter(boolean restore){
        chapter=Math.max(0,Math.min(chapter,book.chapters.size()-1));pageCount=1;
        try{chapterText=book.plain(chapter);if(!restore)ttsPosition=0;paginator.restore(ttsPosition,chapterText.length());File f=book.chapterFile(extracted,chapter);String html=book.html(chapter,theme,fontSize,margin).replace("</head>","<style>body{--reader-font:"+font+"}</style></head>");web.loadDataWithBaseURL(f.getParentFile().toURI().toString(),html,"text/html","UTF-8",null);titleView.setText(book.title);updateIndicator();}
        catch(Exception e){Toast.makeText(this,"Errore pagina: "+e.getMessage(),Toast.LENGTH_LONG).show();}
    }
    private void preparePages(){
        paginator.setMargin(margin);paginator.restore(ttsPosition,Math.max(1,chapterText.length()));paginator.prepare((pages,current)->{pageCount=pages;page=current;if(!pendingAnchor.isEmpty()){String anchor=pendingAnchor;pendingAnchor="";paginator.goToAnchor(anchor,(p,c)->{pageCount=p;page=c;updateIndicator();savePosition(true);});}else{updateIndicator();savePosition(true);}});
    }
    private void moveToPage(boolean animate,int direction){page=paginator.page();ttsPosition=paginator.offset();if(!animate||direction==0){paginator.apply();turnLocked=false;}else{float distance=web.getWidth()*.22f;web.animate().translationX(-direction*distance).alpha(.25f).setDuration(120).withEndAction(()->{paginator.apply();web.setTranslationX(direction*distance);web.animate().translationX(0).alpha(1f).setDuration(170).withEndAction(()->turnLocked=false).start();}).start();}updateIndicator();savePosition(true);}
    private boolean lock(){if(turnLocked)return false;turnLocked=true;return true;}
    private void previousPage(){if(!lock())return;if(paginator.previous())moveToPage(true,-1);else if(chapter>0){chapter--;try{ttsPosition=book.plain(chapter).length();}catch(Exception ignored){ttsPosition=0;}showChapter(true);}else turnLocked=false;}
    private void nextPage(){if(!lock())return;if(paginator.next())moveToPage(true,1);else if(chapter<book.chapters.size()-1){chapter++;ttsPosition=0;showChapter(false);}else{turnLocked=false;Toast.makeText(this,"Fine del libro",Toast.LENGTH_SHORT).show();}}
    private void activateLinkOrToggle(float x,float y){web.evaluateJavascript("(function(){var e=document.elementFromPoint("+x+"/devicePixelRatio,"+y+"/devicePixelRatio),a=e&&e.closest?e.closest('a'):null;if(a){a.click();return true}return false})()",r->{if(!"true".equals(r))toggleChrome();});}
    private void toggleChrome(){
        ttsPosition=paginator.offset();pendingPagePercent=paginator.percent();chromeVisible=!chromeVisible;int state=chromeVisible?View.VISIBLE:View.GONE;
        if(topBar!=null)topBar.setVisibility(state);if(pageView!=null)pageView.setVisibility(state);if(bottomDock!=null)bottomDock.setVisibility(state);
        View decor=getWindow().getDecorView();
        if(chromeVisible)decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        else decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        web.postDelayed(this::preparePages,220);
    }
    private boolean openLink(Uri uri){if(uri==null)return true;String scheme=uri.getScheme();if("http".equalsIgnoreCase(scheme)||"https".equalsIgnoreCase(scheme)||"mailto".equalsIgnoreCase(scheme)){try{startActivity(new Intent(Intent.ACTION_VIEW,uri));}catch(Exception e){Toast.makeText(this,"Nessuna app disponibile per il collegamento",Toast.LENGTH_SHORT).show();}return true;}int c=book.findChapter(uri.getPath());if(c>=0){stopSpeak();chapter=c;ttsPosition=0;pendingAnchor=uri.getFragment()==null?"":uri.getFragment();showChapter(false);return true;}if(uri.getFragment()!=null){pendingAnchor=uri.getFragment();preparePages();}return true;}
    private void updateIndicator(){if(pageView!=null)pageView.setText(book.labels.get(chapter)+"   •   PAGINA "+(page+1)+" / "+pageCount);}
    private void savePosition(){savePosition(true);}private void savePosition(boolean force){if(book==null||settings==null)return;long now=SystemClock.uptimeMillis();if(!force&&now-lastPositionSave<2000)return;lastPositionSave=now;if(paginator!=null&&!speaking)ttsPosition=paginator.offset();settings.savePosition(book.file.getAbsolutePath(),chapter,ttsPosition,chapterText.length());}

    private void speak(){
        try{int from=Math.max(ttsPosition,paginator.offset());ttsController.start(book,chapter,from,settings);}
        catch(Exception e){Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();}
    }
    private void pauseResume(){ttsController.action(paused?TtsService.RESUME:TtsService.PAUSE);}
    private void stopSpeak(){speaking=false;paused=false;ttsController.stop();}
    private void followVoice(String sentence){
        if(sentence==null||sentence.isEmpty())return;
        String b64=Base64.encodeToString(sentence.getBytes(StandardCharsets.UTF_8),Base64.NO_WRAP);
        String js="(function(){document.querySelectorAll('span.tts-current').forEach(function(e){e.replaceWith(document.createTextNode(e.textContent))});var q=new TextDecoder().decode(Uint8Array.from(atob('"+b64+"'),function(c){return c.charCodeAt(0)})).replace(/\\s+/g,' ').trim().substring(0,80);if(!q)return -1;var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT),nodes=[],n,flat='',map=[];while(n=w.nextNode()){for(var j=0;j<n.nodeValue.length;j++){var ch=n.nodeValue[j],space=/\\s/.test(ch);if(space&&flat.endsWith(' '))continue;flat+=space?' ':ch;map.push([n,j]);}}var k=flat.indexOf(q);if(k<0)return -1;var end=Math.min(map.length-1,k+q.length-1),r=document.createRange();r.setStart(map[k][0],map[k][1]);r.setEnd(map[end][0],map[end][1]+1);var s=document.createElement('span');s.className='tts-current';try{s.appendChild(r.extractContents());r.insertNode(s);}catch(e){return -1;}var current=Math.round(Math.abs(parseFloat((document.body.style.transform.match(/-?[0-9.]+/)||[0])[0]||0))/innerWidth),absoluteLeft=s.getBoundingClientRect().left+current*innerWidth,target=Math.max(0,Math.floor(absoluteLeft/innerWidth));document.body.style.transform='translate3d(-'+(target*innerWidth)+'px,0,0)';return target;})()";web.evaluateJavascript(js,r->{try{int target=(int)Math.round(Double.parseDouble(r.replace("\"","")));if(target>=0){paginator.setPage(target);page=paginator.page();updateIndicator();return;}}catch(Exception ignored){}paginator.goToOffset(ttsPosition);page=paginator.page();updateIndicator();});
    }

    private void settings(){
        openMenuCard=null;openMenuHeader=null;
        Dialog dialog=new Dialog(this);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout shell=new LinearLayout(this);shell.setOrientation(LinearLayout.VERTICAL);shell.setPadding(dp(16),dp(14),dp(16),dp(16));shell.setBackground(panel(Color.rgb(7,28,43),22));
        LinearLayout head=new LinearLayout(this);head.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading=new TextView(this);heading.setText("IMPOSTAZIONI LETTURA");heading.setTextColor(Color.WHITE);heading.setTextSize(19);heading.setTypeface(null,android.graphics.Typeface.BOLD);head.addView(heading,new LinearLayout.LayoutParams(0,dp(48),1));
        TextView close=new TextView(this);close.setText("×");close.setTextColor(Color.rgb(255,187,51));close.setTextSize(30);close.setGravity(Gravity.CENTER);close.setOnClickListener(v->dialog.dismiss());head.addView(close,new LinearLayout.LayoutParams(dp(48),dp(48)));shell.addView(head);
        TextView subtitle=new TextView(this);subtitle.setText("Personalizza il libro senza lasciare la pagina");subtitle.setTextColor(Color.rgb(153,184,199));subtitle.setTextSize(12);subtitle.setPadding(0,0,0,dp(10));shell.addView(subtitle);

        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);
        addMenuSection(content,"LIBRO",Color.rgb(72,199,232),new String[]{"Indice del libro","Cerca in tutto il libro","Aggiungi o rimuovi segnalibro","Apri segnalibri","Avanzamento nel libro"},new Runnable[]{this::showIndex,this::search,this::toggleBookmark,this::showBookmarks,this::showProgress},dialog);
        addMenuSection(content,"ASPETTO PAGINA",Color.rgb(255,187,51),new String[]{"Aumenta dimensione testo","Riduci dimensione testo","Margini pagina","Carattere: "+font,"Tema pagina: "+theme},new Runnable[]{()->{fontSize=Math.min(36,fontSize+2);saveLook();},()->{fontSize=Math.max(14,fontSize-2);saveLook();},()->{margin=margin==18?30:margin==30?46:18;saveLook();},this::chooseFont,this::chooseTheme},dialog);
        addMenuSection(content,"LETTURA VOCALE",Color.rgb(235,92,92),new String[]{"Velocità della voce","Voce del libro ("+book.language+")","Timer spegnimento"},new Runnable[]{this::chooseRate,this::chooseVoice,this::chooseTimer},dialog);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.addView(content);shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        dialog.setContentView(shell);dialog.show();Window window=dialog.getWindow();if(window!=null){window.setBackgroundDrawableResource(android.R.color.transparent);window.setDimAmount(.72f);window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);window.setLayout((int)(getResources().getDisplayMetrics().widthPixels*.92f),(int)(getResources().getDisplayMetrics().heightPixels*.82f));}
    }
    private void addMenuSection(LinearLayout parent,String title,int accent,String[] labels,Runnable[] actions,Dialog dialog){
        TextView section=new TextView(this);section.setText(title+"     ▸");section.setTextColor(accent);section.setTextSize(12);section.setLetterSpacing(.12f);section.setTypeface(null,android.graphics.Typeface.BOLD);section.setGravity(Gravity.CENTER_VERTICAL);section.setPadding(dp(12),dp(8),dp(10),dp(8));GradientDrawable sectionBg=panel(Color.rgb(10,39,58),14);sectionBg.setStroke(dp(1),Color.argb(105,Color.red(accent),Color.green(accent),Color.blue(accent)));section.setBackground(sectionBg);LinearLayout.LayoutParams sectionLp=new LinearLayout.LayoutParams(-1,dp(48));sectionLp.setMargins(0,dp(7),0,0);parent.addView(section,sectionLp);
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(6),dp(5),dp(6),dp(5));GradientDrawable bg=panel(Color.rgb(10,39,58),16);bg.setStroke(dp(1),Color.argb(105,Color.red(accent),Color.green(accent),Color.blue(accent)));card.setBackground(bg);
        for(int i=0;i<labels.length;i++){TextView row=new TextView(this);row.setText(labels[i]+"   ›");row.setTextColor(Color.rgb(235,244,248));row.setTextSize(15);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(14),0,dp(12),0);final Runnable action=actions[i];row.setOnClickListener(v->{dialog.dismiss();action.run();});card.addView(row,new LinearLayout.LayoutParams(-1,dp(46)));if(i<labels.length-1){View line=new View(this);line.setBackgroundColor(Color.rgb(28,66,85));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(1));lp.setMargins(dp(14),0,dp(14),0);card.addView(line,lp);}}
        card.setVisibility(View.GONE);LinearLayout.LayoutParams cardLp=new LinearLayout.LayoutParams(-1,-2);cardLp.setMargins(0,dp(4),0,0);parent.addView(card,cardLp);
        section.setOnClickListener(v->{boolean opening=card.getVisibility()!=View.VISIBLE;if(openMenuCard!=null&&openMenuCard!=card){openMenuCard.setVisibility(View.GONE);if(openMenuHeader!=null)openMenuHeader.setText(openMenuHeader.getTag()+"     ▸");}card.setVisibility(opening?View.VISIBLE:View.GONE);section.setText(title+(opening?"     ▾":"     ▸"));openMenuCard=opening?card:null;openMenuHeader=opening?section:null;});section.setTag(title);
    }
    private void chooseTheme(){
        String[] names={"Bianco","Seppia","Sabbia","Verde salvia","Blu notte","Nero"};int[] colors={Color.WHITE,Color.rgb(252,248,238),Color.rgb(241,227,198),Color.rgb(223,233,221),Color.rgb(11,31,45),Color.rgb(16,16,16)};
        Dialog dialog=new Dialog(this);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);LinearLayout shell=new LinearLayout(this);shell.setOrientation(LinearLayout.VERTICAL);shell.setPadding(dp(16),dp(14),dp(16),dp(16));shell.setBackground(panel(Color.rgb(7,28,43),20));
        TextView heading=new TextView(this);heading.setText("COLORE DELLA PAGINA");heading.setTextColor(Color.WHITE);heading.setTextSize(18);heading.setTypeface(null,android.graphics.Typeface.BOLD);heading.setPadding(dp(4),0,0,dp(10));shell.addView(heading);
        String selected="Chiaro".equals(theme)?"Bianco":"Scuro".equals(theme)?"Nero":theme;
        for(int i=0;i<names.length;i++){LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(10),0,dp(12),0);GradientDrawable rowBg=panel(Color.rgb(10,39,58),13);rowBg.setStroke(dp(1),names[i].equals(selected)?Color.rgb(255,187,51):Color.rgb(28,66,85));row.setBackground(rowBg);View swatch=new View(this);swatch.setBackground(panel(colors[i],9));row.addView(swatch,new LinearLayout.LayoutParams(dp(38),dp(38)));TextView label=new TextView(this);label.setText(names[i]+(names[i].equals(selected)?"   ✓":""));label.setTextColor(Color.rgb(235,244,248));label.setTextSize(16);label.setPadding(dp(14),0,0,0);row.addView(label,new LinearLayout.LayoutParams(0,-1,1));final String value=names[i];row.setOnClickListener(v->{theme=value;dialog.dismiss();saveLook();});LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(54));lp.setMargins(0,dp(4),0,dp(4));shell.addView(row,lp);}
        dialog.setContentView(shell);dialog.show();Window window=dialog.getWindow();if(window!=null){window.setBackgroundDrawableResource(android.R.color.transparent);window.setDimAmount(.72f);window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);window.setLayout((int)(getResources().getDisplayMetrics().widthPixels*.88f),WindowManager.LayoutParams.WRAP_CONTENT);}
    }
    private void saveLook(){ttsPosition=paginator.offset();settings.fontSize=fontSize;settings.margin=margin;settings.theme=theme;settings.font=font;settings.saveLook();showChapter(true);}
    private void showIndex(){
        if(book.tocEntries.isEmpty()){String[] a=book.labels.toArray(new String[0]);new AlertDialog.Builder(this).setTitle("Indice del libro").setItems(a,(d,w)->{stopSpeak();chapter=w;ttsPosition=0;showChapter(false);}).show();return;}
        String[] a=new String[book.tocEntries.size()];for(int i=0;i<a.length;i++)a[i]=book.tocEntries.get(i).label;
        new AlertDialog.Builder(this).setTitle("Indice del libro").setItems(a,(d,w)->{stopSpeak();EpubBook.TocEntry entry=book.tocEntries.get(w);chapter=entry.chapter;ttsPosition=0;pendingAnchor=entry.anchor;showChapter(false);}).show();
    }
    private void search(){EditText e=new EditText(this);e.setHint("Parola o frase");new AlertDialog.Builder(this).setTitle("Cerca in tutto il libro").setView(e).setPositiveButton("Cerca",(d,w)->searchBook(e.getText().toString().trim())).setNegativeButton("Annulla",null).show();}
    private void searchBook(String query){if(query.isEmpty())return;ProgressDialog progress=ProgressDialog.show(this,"Ricerca","Cerco in tutti i capitoli…",true,false);new Thread(()->{ArrayList<Integer> chapters=new ArrayList<>(),offsets=new ArrayList<>();ArrayList<String> names=new ArrayList<>();String needle=query.toLowerCase(Locale.ROOT);try{for(int c=0;c<book.chapters.size();c++){String text=book.plain(c);int from=0,found;while((found=text.toLowerCase(Locale.ROOT).indexOf(needle,from))>=0&&names.size()<100){int start=Math.max(0,found-35),end=Math.min(text.length(),found+query.length()+55);names.add(book.labels.get(c)+" • …"+text.substring(start,end).replace('\n',' ')+"…");chapters.add(c);offsets.add(found);from=found+Math.max(1,query.length());}}}catch(Exception ignored){}runOnUiThread(()->{progress.dismiss();if(names.isEmpty()){Toast.makeText(this,"Nessun risultato",Toast.LENGTH_SHORT).show();return;}new AlertDialog.Builder(this).setTitle("Risultati: "+names.size()).setItems(names.toArray(new String[0]),(d,index)->{chapter=chapters.get(index);ttsPosition=offsets.get(index);showChapter(true);}).show();});}).start();}
    private String bookmarksKey(){return key("bookmarks");}
    private void migrateBookmarks(){String done=key("bookmarks_offset_v2");if(prefs.getBoolean(done,false))return;Set<String> old=prefs.getStringSet(bookmarksKey(),Collections.emptySet());boolean legacy=false;for(String value:old)if(!value.startsWith("o:")){legacy=true;break;}android.content.SharedPreferences.Editor edit=prefs.edit().putBoolean(done,true);if(legacy){edit.remove(bookmarksKey());Toast.makeText(this,"Segnalibri della vecchia impaginazione azzerati",Toast.LENGTH_LONG).show();}edit.apply();}
    private void toggleBookmark(){int offset=paginator.offset();String mark="o:"+chapter+":"+offset;Set<String>s=new HashSet<>(prefs.getStringSet(bookmarksKey(),Collections.emptySet()));String existing=null;for(String v:s){String[]p=v.split(":");if(p.length==3&&"o".equals(p[0])&&Integer.parseInt(p[1])==chapter&&Math.abs(Integer.parseInt(p[2])-offset)<Math.max(40,chapterText.length()/100)){existing=v;break;}}if(existing!=null){s.remove(existing);Toast.makeText(this,"Segnalibro rimosso",Toast.LENGTH_SHORT).show();}else{s.add(mark);Toast.makeText(this,"Segnalibro aggiunto",Toast.LENGTH_SHORT).show();}prefs.edit().putStringSet(bookmarksKey(),s).apply();}
    private void showBookmarks(){ArrayList<String> raw=new ArrayList<>();for(String value:prefs.getStringSet(bookmarksKey(),Collections.emptySet()))if(value.startsWith("o:"))raw.add(value);raw.sort(Comparator.comparingInt(x->{String[]p=x.split(":");return Integer.parseInt(p[1])*10000000+Integer.parseInt(p[2]);}));String[] names=new String[raw.size()];for(int i=0;i<raw.size();i++){String[]p=raw.get(i).split(":");int c=Integer.parseInt(p[1]),o=Integer.parseInt(p[2]);int pct=0;try{pct=(int)(100.0*o/Math.max(1,book.plain(c).length()));}catch(Exception ignored){}names[i]=book.labels.get(c)+" • "+pct+"%";}new AlertDialog.Builder(this).setTitle("Segnalibri").setItems(names,(d,w)->{String[]p=raw.get(w).split(":");chapter=Integer.parseInt(p[1]);ttsPosition=Integer.parseInt(p[2]);showChapter(true);}).show();}
    private void showProgress(){SeekBar bar=new SeekBar(this);bar.setMax(Math.max(1,book.chapters.size()*100-1));bar.setProgress(chapter*100+(int)(100.0*paginator.offset()/Math.max(1,chapterText.length())));new AlertDialog.Builder(this).setTitle("Avanzamento nel libro").setView(bar).setPositiveButton("Vai",(d,w)->{chapter=Math.min(book.chapters.size()-1,bar.getProgress()/100);try{ttsPosition=(int)((bar.getProgress()%100)/100.0*book.plain(chapter).length());}catch(Exception ignored){ttsPosition=0;}showChapter(true);}).setNegativeButton("Annulla",null).show();}
    private void chooseFont(){String[]a={"Georgia","sans-serif","serif","monospace"};new AlertDialog.Builder(this).setTitle("Carattere").setItems(a,(d,w)->{font=a[w];saveLook();}).show();}
    private void chooseRate(){String[]a={"0,75×","0,90×","1×","1,15×","1,30×"};float[]v={.75f,.9f,1f,1.15f,1.3f};new AlertDialog.Builder(this).setTitle("Velocità voce").setItems(a,(d,w)->prefs.edit().putFloat("rate",v[w]).apply()).show();}
    private void chooseVoice(){
        final android.speech.tts.TextToSpeech[] engine=new android.speech.tts.TextToSpeech[1];
        engine[0]=new android.speech.tts.TextToSpeech(this,status->{
            if(isFinishing()||isDestroyed()){if(engine[0]!=null)engine[0].shutdown();return;}
            if(status!=android.speech.tts.TextToSpeech.SUCCESS){Toast.makeText(this,"Sintesi vocale non disponibile",Toast.LENGTH_LONG).show();if(engine[0]!=null)engine[0].shutdown();return;}
            ArrayList<android.speech.tts.Voice> voices=new ArrayList<>();
            try{
                Locale wanted=Locale.forLanguageTag(book.language.replace('_','-'));engine[0].setLanguage(wanted);
                for(android.speech.tts.Voice v:engine[0].getVoices()){
                    if(v.getLocale()!=null&&wanted.getLanguage().equals(v.getLocale().getLanguage())&&!v.isNetworkConnectionRequired())voices.add(v);
                }
                voices.sort(Comparator.comparing(android.speech.tts.Voice::getName));
                if(voices.size()>2)voices.subList(2,voices.size()).clear();
            }catch(Exception ignored){}
            String[] names=new String[voices.size()+1];names[0]="Automatica dalla lingua del libro";
            for(int i=0;i<voices.size();i++)names[i+1]="Voce "+(i+1)+" • "+voices.get(i).getLocale().getDisplayLanguage()+" • offline";
            new AlertDialog.Builder(this).setTitle("Voce di lettura").setItems(names,(d,w)->{
                String voiceKey="voice_"+TtsController.languageKey(book.language);
                if(w==0)prefs.edit().remove(voiceKey).apply();
                else prefs.edit().putString(voiceKey,voices.get(w-1).getName()).apply();
                try{engine[0].shutdown();}catch(Exception ignored){}
                Toast.makeText(this,"Voce selezionata",Toast.LENGTH_SHORT).show();
            }).setOnCancelListener(d->{try{engine[0].shutdown();}catch(Exception ignored){}}).show();
        });
    }
    private void chooseTimer(){String[]a={"Disattivato","15 minuti","30 minuti","45 minuti","60 minuti"};int[]v={0,15,30,45,60};new AlertDialog.Builder(this).setTitle("Timer spegnimento").setItems(a,(d,w)->{sleepTimer=v[w];settings.sleepTimer=sleepTimer;prefs.edit().putInt("timer",sleepTimer).remove("tts_timer_deadline").apply();}).show();}
    @Override protected void onDestroy(){if(book!=null&&!speaking)savePosition();try{unregisterReceiver(voiceReceiver);}catch(Exception ignored){}try{if(book!=null)book.close();}catch(Exception ignored){}if(web!=null)web.destroy();super.onDestroy();}
}
