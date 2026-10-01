package com.paolofree.lettoreepub;

import android.webkit.WebView;

/** Calcola e sposta le colonne della WebView conservando un offset testuale stabile. */
public final class Paginator {
    public interface Ready { void onReady(int pages,int page); }
    private final WebView web;
    private int pageCount=1,page=0,margin=30,textLength=1,textOffset=0;
    public Paginator(WebView web){this.web=web;}
    public void setMargin(int value){margin=value;}
    public void restore(int offset,int length){textOffset=Math.max(0,offset);textLength=Math.max(1,length);}
    public int page(){return page;} public int pages(){return pageCount;}
    public int offset(){return Math.min(textLength,(int)Math.round((page/(double)Math.max(1,pageCount))*textLength));}
    public double percent(){return textOffset/(double)Math.max(1,textLength);}
    public void prepare(Ready ready){
        String js="(function(){var w=innerWidth,h=innerHeight,m="+margin+";document.documentElement.style.cssText+=';width:'+w+'px!important;height:'+h+'px!important;margin:0!important;padding:0!important;overflow:hidden!important';document.body.style.cssText+=';box-sizing:border-box!important;width:'+w+'px!important;max-width:none!important;height:'+h+'px!important;max-height:'+h+'px!important;margin:0!important;padding:24px '+m+'px 32px!important;overflow:visible!important;column-width:'+(w-2*m)+'px!important;column-gap:'+(2*m)+'px!important;column-fill:auto!important;transform-origin:0 0!important;position:relative!important';return Math.max(1,Math.ceil(document.body.scrollWidth/w));})()";
        web.evaluateJavascript(js,r->{try{pageCount=Math.max(1,(int)Math.ceil(Double.parseDouble(r.replace("\"",""))));}catch(Exception ignored){pageCount=1;}page=Math.min(pageCount-1,(int)Math.floor((textOffset/(double)Math.max(1,textLength))*pageCount));move();ready.onReady(pageCount,page);});
    }
    public boolean next(){if(page>=pageCount-1)return false;page++;textOffset=offset();move();return true;}
    public boolean previous(){if(page<=0)return false;page--;textOffset=offset();move();return true;}
    public void goToOffset(int offset){textOffset=Math.max(0,Math.min(textLength,offset));page=Math.min(pageCount-1,(int)Math.floor((textOffset/(double)Math.max(1,textLength))*pageCount));move();}
    public void goToAnchor(String anchor,Ready ready){
        String safe=anchor==null?"":anchor.replace("\\","\\\\").replace("'","\\'");
        web.evaluateJavascript("(function(){var e=document.getElementById('"+safe+"')||document.querySelector('[name=\""+safe.replace("\"","\\\"")+"\"]');return e?Math.max(0,e.getBoundingClientRect().left+(-parseFloat((document.body.style.transform.match(/-?[0-9.]+/)||[0])[0]||0))):-1})()",r->{try{double x=Double.parseDouble(r.replace("\"",""));if(x>=0){page=Math.min(pageCount-1,(int)Math.floor(x/Math.max(1,web.getWidth())));textOffset=offset();move();}}catch(Exception ignored){}ready.onReady(pageCount,page);});
    }
    private void move(){web.evaluateJavascript("(function(){document.body.style.transform='translate3d(-'+("+page+"*innerWidth)+'px,0,0)'})()",null);}
}
